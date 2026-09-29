package com.sysadmindoc.alarmclock.data.cloud

import android.content.Context
import com.sysadmindoc.alarmclock.BuildConfig
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import com.sysadmindoc.alarmclock.domain.NextAlarmCalculator
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bidirectional alarm sync.
 *
 * The cloud stores user alarm intent. Android-only scheduler state (Room id and
 * nextTriggerTime) stays local. A last-synced snapshot lets us tell a remote
 * edit from an unrelated local scheduler update, preventing web changes from
 * being silently overwritten by stale Room state.
 */
@Singleton
class CloudSyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: CloudApi,
    private val moshi: Moshi,
    private val repository: AlarmRepository,
    private val scheduler: AlarmScheduler,
    private val calculator: NextAlarmCalculator
) {
    private val prefs = CloudPreferences(context)
    private val mutex = Mutex()

    @Volatile
    private var applyingRemote = false

    private val alarmAdapter = moshi.adapter(Alarm::class.java)

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

            val mapping = prefs.getMapping().toMutableMap()
            val snapshots = prefs.getSnapshots().toMutableMap()
            val versions = prefs.getVersions().toMutableMap()

            val since = if (forceFull) EPOCH else prefs.getCursor()
            val remote = api.getAlarms(auth(), since)
            var cursor = remote.cursor

            applyingRemote = true
            try {
                for (item in remote.alarms.sortedBy { it.updatedAt }) {
                    cursor = maxTimestamp(cursor, item.updatedAt)

                    val localId = mapping[item.id]
                    val local = localId?.let { repository.getById(it) }
                        ?: findEquivalentLocal(item, mapping.values.toSet())

                    if (item.deletedAt != null) {
                        if (local == null) {
                            mapping.remove(item.id)
                            snapshots.remove(item.id)
                            versions.remove(item.id)
                            continue
                        }

                        val localChanged = canonical(local) != snapshots[item.id]
                        if (!localChanged) {
                            repository.deleteById(local.id)
                            mapping.remove(item.id)
                            snapshots.remove(item.id)
                            versions.remove(item.id)
                        }
                        continue
                    }

                    val remoteAlarm = decodeAlarm(item) ?: continue
                    if (local == null) {
                        val newId = repository.save(remoteAlarm.copy(id = 0L, nextTriggerTime = 0L))
                        val saved = repository.getById(newId) ?: remoteAlarm.copy(id = newId)
                        applySchedule(saved)
                        mapping[item.id] = newId
                        snapshots[item.id] = canonical(saved)
                        versions[item.id] = item.version
                        continue
                    }

                    mapping[item.id] = local.id
                    val localChanged = canonical(local) != snapshots[item.id]
                    if (localChanged) {
                        // Local user edit wins this conflict. The subsequent
                        // push uses the current remote version as its guard.
                        versions[item.id] = item.version
                        continue
                    }

                    val updated = remoteAlarm.copy(
                        id = local.id,
                        nextTriggerTime = 0L
                    )
                    repository.update(updated)
                    applySchedule(updated)
                    snapshots[item.id] = canonical(updated)
                    versions[item.id] = item.version
                }
            } finally {
                applyingRemote = false
            }

            pushLocalChanges(mapping, snapshots, versions) { updatedAt ->
                cursor = maxTimestamp(cursor, updatedAt)
            }

            // Store sync metadata only after all remote and local operations
            // completed. A failed push leaves the old cursor/snapshot intact,
            // so the next run retries rather than losing the change.
            prefs.setMapping(mapping)
            prefs.setSnapshots(snapshots)
            prefs.setVersions(versions)
            prefs.setCursor(cursor)
        }
    }

    suspend fun observeLocalChanges() {
        repository.observeAll()
            .distinctUntilChanged()
            .debounce(1500L)
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

    private suspend fun pushLocalChanges(
        mapping: MutableMap<String, Long>,
        snapshots: MutableMap<String, String>,
        versions: MutableMap<String, Long>,
        onUpdatedAt: (String) -> Unit
    ) {
        val local = repository.getAll()
        val localIds = local.map { it.id }.toSet()

        // Room deletions become cloud tombstones.
        for ((remoteId, localId) in mapping.toMap()) {
            if (localId !in localIds) {
                val expected = versions[remoteId] ?: 0L
                try {
                    val response = api.deleteAlarm(auth(), remoteId, expected)
                    onUpdatedAt(response.updatedAt)
                } catch (e: HttpException) {
                    if (e.code() != 404 && e.code() != 409) throw e
                    val latest = fetchRemoteItem(remoteId)
                    if (latest?.deletedAt == null) {
                        val response = api.deleteAlarm(auth(), remoteId, latest.version)
                        onUpdatedAt(response.updatedAt)
                    }
                }
                mapping.remove(remoteId)
                snapshots.remove(remoteId)
                versions.remove(remoteId)
            }
        }

        val inverse = mapping.entries.associate { it.value to it.key }

        for (alarm in local) {
            val remoteId = inverse[alarm.id] ?: UUID.randomUUID().toString()
            val localPayload = canonical(alarm)
            val snapshot = snapshots[remoteId]

            if (snapshot == null || localPayload != snapshot) {
                val expected = versions[remoteId] ?: 0L
                val response = try {
                    api.putAlarm(
                        auth(),
                        remoteId,
                        CloudAlarmWriteRequest(
                            payload = alarmPayload(alarm),
                            expectedVersion = expected
                        )
                    )
                } catch (e: HttpException) {
                    if (e.code() != 409) throw e
                    val latest = fetchRemoteItem(remoteId)
                        ?: throw IllegalStateException("remote_alarm_missing")
                    if (latest.deletedAt != null) {
                        api.putAlarm(
                            auth(),
                            remoteId,
                            CloudAlarmWriteRequest(
                                payload = alarmPayload(alarm),
                                expectedVersion = latest.version
                            )
                        )
                    } else {
                        api.putAlarm(
                            auth(),
                            remoteId,
                            CloudAlarmWriteRequest(
                                payload = alarmPayload(alarm),
                                expectedVersion = latest.version
                            )
                        )
                    }
                }

                mapping[remoteId] = alarm.id
                snapshots[remoteId] = localPayload
                versions[remoteId] = response.version
                onUpdatedAt(response.updatedAt)
            } else {
                mapping[remoteId] = alarm.id
            }
        }
    }

    private suspend fun fetchRemoteItem(remoteId: String): CloudAlarmDto? {
        val remote = api.getAlarms(auth(), EPOCH)
        return remote.alarms.firstOrNull { it.id == remoteId }
    }

    private suspend fun findEquivalentLocal(
        item: CloudAlarmDto,
        mappedIds: Set<Long>
    ): Alarm? {
        val remote = decodeAlarm(item) ?: return null
        return repository.getAll()
            .firstOrNull { it.id !in mappedIds && canonical(it) == canonical(remote) }
    }

    private fun decodeAlarm(item: CloudAlarmDto): Alarm? {
        return runCatching {
            alarmAdapter.fromJson(mapToJson(item.payload))
        }.getOrNull()
    }

    private fun alarmPayload(alarm: Alarm): Map<String, Any?> {
        val json = canonical(alarm)
        @Suppress("UNCHECKED_CAST")
        return moshi.adapter<Map<String, Any?>>(
            TypesHolder.mapType
        ).fromJson(json) ?: emptyMap()
    }

    private fun canonical(alarm: Alarm): String {
        val normalized = alarm.sanitized().copy(
            id = 0L,
            nextTriggerTime = 0L,
            repeatDays = alarm.repeatDays.sortedBy { it.value }.toSet()
        )
        return alarmAdapter.toJson(normalized)
    }

    private suspend fun applySchedule(alarm: Alarm) {
        if (!alarm.isEnabled) {
            repository.updateNextTrigger(alarm.id, 0L)
            scheduler.cancel(alarm.id)
            return
        }
        val trigger = calculator.calculate(alarm)
        repository.updateNextTrigger(alarm.id, trigger)
        scheduler.schedule(alarm.copy(nextTriggerTime = trigger))
    }

    private fun mapToJson(payload: Map<String, Any?>): String =
        moshi.adapter<Map<String, Any?>>(TypesHolder.mapType).toJson(payload)

    private fun maxTimestamp(a: String, b: String): String =
        if (a >= b) a else b

    private fun auth(): String = "Bearer " + prefs.getToken()

    private object TypesHolder {
        val mapType = com.squareup.moshi.Types.newParameterizedType(
            Map::class.java,
            String::class.java,
            Any::class.java
        )
    }

    private companion object {
        const val EPOCH = "1970-01-01T00:00:00Z"
    }
}