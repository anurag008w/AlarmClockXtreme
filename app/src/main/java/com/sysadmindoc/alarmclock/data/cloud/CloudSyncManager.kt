package com.sysadmindoc.alarmclock.data.cloud

import android.content.Context
import com.sysadmindoc.alarmclock.BuildConfig
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import com.sysadmindoc.alarmclock.domain.NextAlarmCalculator
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CloudSyncManager @Inject constructor(
    private val context: Context,
    private val api: CloudApi,
    private val moshi: Moshi,
    private val repository: AlarmRepository,
    private val scheduler: AlarmScheduler,
    private val calculator: NextAlarmCalculator
) {
    private val prefs = CloudPreferences(context)
    private val mutex = Mutex()
    @Volatile private var applyingRemote = false

    private val alarmAdapter = moshi.adapter(Alarm::class.java)
    private val mapAdapter = moshi.adapter<Map<String, Any?>>(
        Types.newParameterizedType(
            Map::class.java,
            String::class.java,
            Any::class.java
        )
    )

    fun isLoggedIn(): Boolean = prefs.isLoggedIn()
    fun email(): String = prefs.getEmail()
    fun baseUrl(): String = BuildConfig.CLOUD_BASE_URL

    suspend fun login(email: String, password: String): Result<String> = runCatching {
        val response = api.login(CloudAuthRequest(email.trim(), password))
        prefs.saveSession(response.token, response.user.email)
        registerDevice()
        syncNow(forceFull = true).getOrThrow()
        response.user.email
    }

    suspend fun register(email: String, password: String): Result<String> = runCatching {
        val response = api.register(CloudAuthRequest(email.trim(), password))
        prefs.saveSession(response.token, response.user.email)
        registerDevice()
        syncNow(forceFull = true).getOrThrow()
        response.user.email
    }

    fun logout() {
        prefs.clearSession()
    }

    suspend fun sendAiCommand(command: String): Result<String> = runCatching {
        require(isLoggedIn()) { "not_logged_in" }
        val response = api.aiCommand(auth(), CloudAiRequest(command.trim()))
        syncNow(forceFull = false).getOrThrow()
        response.message ?: "done"
    }

    suspend fun syncNow(forceFull: Boolean = false): Result<Unit> = runCatching {
        mutex.withLock {
            if (!isLoggedIn()) return@withLock
            registerDevice()
            val since = if (forceFull) "1970-01-01T00:00:00.000Z" else prefs.getCursor()
            val remote = api.getAlarms(auth(), since)

            applyingRemote = true
            try {
                val mapping = prefs.getMapping().toMutableMap()
                for (item in remote.alarms) {
                    applyRemoteItem(item, mapping)
                }
                prefs.setMapping(mapping)
                prefs.setCursor(remote.cursor)
            } finally {
                applyingRemote = false
            }

            pushLocalChanges()
        }
    }

    /**
     * Watches the Room-backed alarm list. Native alarm edits therefore become
     * cloud edits without every UI surface needing to know about sync.
     */
    suspend fun observeLocalChanges() {
        repository.observeAll()
            .distinctUntilChanged()
            .debounce(750)
            .collectLatest {
                if (!applyingRemote && isLoggedIn()) {
                    runCatching { syncNow() }
                }
            }
    }

    private suspend fun registerDevice() {
        if (!isLoggedIn()) return
        api.registerDevice(
            auth(),
            CloudDeviceRequest(
                deviceId = prefs.getDeviceId(),
                appVersion = BuildConfig.VERSION_NAME
            )
        )
    }

    private suspend fun applyRemoteItem(
        item: CloudAlarmDto,
        mapping: MutableMap<String, Long>
    ) {
        val existingLocalId = mapping[item.id]

        if (item.deletedAt != null) {
            if (existingLocalId != null) {
                repository.deleteById(existingLocalId)
                mapping.remove(item.id)
            }
            return
        }

        val json = mapAdapter.toJson(item.payload)
        val alarm = alarmAdapter.fromJson(json) ?: return

        if (existingLocalId != null) {
            val existing = repository.getById(existingLocalId)
            if (existing != null) {
                val updated = alarm.copy(id = existingLocalId)
                repository.update(updated)
                schedule(updated)
                return
            }
        }

        val localId = repository.save(alarm.copy(id = 0L))
        val saved = repository.getById(localId) ?: alarm.copy(id = localId)
        if (saved.isEnabled) schedule(saved)
        mapping[item.id] = localId
    }

    private suspend fun pushLocalChanges() {
        val mapping = prefs.getMapping().toMutableMap()
        val inverse = mapping.entries.associate { it.value to it.key }
        val local = repository.getAll()
        val localIds = local.map { it.id }.toSet()

        // A local delete is a cloud tombstone. Remote application of a tombstone
        // is suppressed above, so it cannot immediately re-create/delete-loop.
        for ((remoteId, localId) in mapping.toMap()) {
            if (localId !in localIds) {
                runCatching { api.deleteAlarm(auth(), remoteId) }
                mapping.remove(remoteId)
            }
        }

        for (alarm in local) {
            val remoteId = inverse[alarm.id] ?: UUID.randomUUID().toString()
            val payloadJson = alarmAdapter.toJson(alarm.copy(id = 0L))
            val payload = mapAdapter.fromJson(payloadJson) ?: continue
            val response = api.putAlarm(
                auth(),
                remoteId,
                CloudAlarmWriteRequest(payload = payload)
            )
            mapping[remoteId] = alarm.id
            prefs.setCursor(response.updatedAt)
        }

        prefs.setMapping(mapping)
    }

    private fun auth(): String = "Bearer " + prefs.getToken()

    private suspend fun schedule(alarm: Alarm) {
        if (!alarm.isEnabled) return
        val trigger = calculator.calculate(alarm)
        repository.updateNextTrigger(alarm.id, trigger)
        scheduler.schedule(alarm.copy(nextTriggerTime = trigger))
    }
}