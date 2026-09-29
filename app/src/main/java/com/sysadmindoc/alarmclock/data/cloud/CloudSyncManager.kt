package com.sysadmindoc.alarmclock.data.cloud

import android.content.Context
import com.sysadmindoc.alarmclock.BuildConfig
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import com.sysadmindoc.alarmclock.domain.NextAlarmCalculator
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bidirectional alarm sync.
 *
 * The cloud stores user alarm intent. Android-only scheduler state (Room id and
 * nextTriggerTime) stays local. A last-synced snapshot lets us tell a local
 * edit from a remote edit.
 *
 * Sync is deliberately write-first: local create/edit/delete is pushed before
 * remote changes are pulled. This prevents a fast remote watchdog from
 * replacing a just-made local change with stale cloud data.
 *
 * Local mutations are durable through Room + sync metadata. Successful cloud
 * writes persist their mapping/version immediately, so a process death after a
 * server-side commit cannot create a duplicate or resurrect a delete.
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

    private val alarmAdapter: com.squareup.moshi.JsonAdapter<Alarm> by lazy {
        moshi.adapter(Alarm::class.java)
    }

    private val mapAdapter: com.squareup.moshi.JsonAdapter<Map<String, Any?>> by lazy {
        moshi.adapter(TypesHolder.mapType)
    }

    private val anyAdapter: com.squareup.moshi.JsonAdapter<Any> by lazy {
        moshi.adapter(Any::class.java)
    }

    fun isLoggedIn(): Boolean = prefs.isLoggedIn()
    fun email(): String = prefs.getEmail()
    fun baseUrl(): String = BuildConfig.CLOUD_BASE_URL

    suspend fun login(email: String, password: String): Result<String> = runCatching {
        val response = api.login(CloudAuthRequest(email.trim(), password))
        prefs.saveSession(response.token, response.user.email)
        runCatching { registerDevice() }
        runCatching { syncNow(forceFull = true) }
        response.user.email
    }

    suspend fun register(email: String, password: String): Result<String> = runCatching {
        val response = api.register(CloudAuthRequest(email.trim(), password))
        prefs.saveSession(response.token, response.user.email)
        runCatching { registerDevice() }
        runCatching { syncNow(forceFull = true) }
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
            var cursor = since

            // Local intent goes first. This is the key ordering guarantee for
            // fast create/edit/delete propagation and stale-remote protection.
            pushLocalChanges(mapping, snapshots, versions) { updatedAt ->
                cursor = maxTimestamp(cursor, updatedAt)
            }

            val remote = api.getAlarms(auth(), since)
            cursor = maxTimestamp(cursor, remote.cursor)

            applyingRemote = true
            try {
                for (item in remote.alarms.sortedBy { it.updatedAt }) {
                    cursor = maxTimestamp(cursor, item.updatedAt)

                    val localId = mapping[item.id]
                    var local = localId?.let { repository.getById(it) }

                    // First login/install fallback: reuse an existing Android
                    // alarm when its canonical cloud intent is identical.
                    if (local == null && item.deletedAt == null) {
                        val remoteAlarmForMatch = decodeAlarm(item)
                        if (remoteAlarmForMatch != null) {
                            val usedIds = mapping.values.toSet()
                            local = repository.getAll().firstOrNull { candidate ->
                                candidate.id !in usedIds &&
                                    canonical(candidate) == canonical(remoteAlarmForMatch)
                            }
                            if (local != null) mapping[item.id] = local.id
                        }
                    }

                    if (item.deletedAt != null) {
                        // A cloud tombstone always wins over a stale Android
                        // copy. Never resurrect a remotely deleted alarm.
                        if (local != null) repository.deleteById(local.id)
                        mapping.remove(item.id)
                        snapshots.remove(item.id)
                        versions.remove(item.id)
                        continue
                    }

                    val remoteAlarm = decodeAlarm(item) ?: continue
                    if (local == null) {
                        val newId = repository.save(
                            remoteAlarm.copy(id = 0L, nextTriggerTime = 0L)
                        )
                        val saved = repository.getById(newId)
                            ?: remoteAlarm.copy(id = newId, nextTriggerTime = 0L)
                        applySchedule(saved)
                        mapping[item.id] = newId
                        snapshots[item.id] = canonical(saved)
                        versions[item.id] = item.version
                        continue
                    }

                    mapping[item.id] = local.id
                    val localChanged = canonical(local) != snapshots[item.id]
                    if (localChanged) {
                        // The local row changed after the push scan. Do not
                        // replace it with remote data; the next local watchdog
                        // pass will push the new local intent.
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

            persistMetadata(mapping, snapshots, versions)
            prefs.setCursor(cursor)
        }
    }

    @OptIn(FlowPreview::class)
    /**
     * Lightweight in-process watchdog for Android create/edit/delete.
     * Room invalidation wakes this collector; the short debounce coalesces
     * rapid editor writes without a polling loop or wake lock.
     */
    suspend fun observeLocalChanges() {
        repository.observeAll()
            .distinctUntilChanged()
            .debounce(300L)
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

        // A missing mapped Room row is a local delete. Tombstone it in the
        // cloud before any remote pull can resurrect the stale row.
        for ((remoteId, localId) in mapping.toMap()) {
            if (localId !in localIds) {
                val expected = versions[remoteId] ?: 0L
                try {
                    val response = api.deleteAlarm(auth(), remoteId, expected)
                    onUpdatedAt(response.updatedAt)
                    mapping.remove(remoteId)
                    snapshots.remove(remoteId)
                    versions.remove(remoteId)
                    persistMetadata(mapping, snapshots, versions)
                } catch (e: HttpException) {
                    when (e.code()) {
                        404 -> {
                            mapping.remove(remoteId)
                            snapshots.remove(remoteId)
                            versions.remove(remoteId)
                            persistMetadata(mapping, snapshots, versions)
                        }
                        409 -> {
                            val latest = fetchRemoteItem(remoteId)
                                ?: throw IllegalStateException("remote_alarm_missing")
                            if (latest.deletedAt != null) {
                                mapping.remove(remoteId)
                                snapshots.remove(remoteId)
                                versions.remove(remoteId)
                                persistMetadata(mapping, snapshots, versions)
                            } else {
                                // A concurrent remote edit is preserved. Restore
                                // the newest cloud copy locally and let the user
                                // explicitly delete it again if still desired.
                                val restored = saveRemoteCopy(localId, latest)
                                mapping[remoteId] = restored.id
                                snapshots[remoteId] = canonical(restored)
                                versions[remoteId] = latest.version
                                onUpdatedAt(latest.updatedAt)
                                persistMetadata(mapping, snapshots, versions)
                            }
                        }
                        else -> throw e
                    }
                }
            }
        }

        // New local alarms get a deterministic remote id based on this device
        // and the Room id. This closes the process-death window between a
        // successful create and persisting the mapping.
        val inverse = mapping.entries.associate { it.value to it.key }

        for (alarm in local) {
            var remoteId = inverse[alarm.id] ?: stableRemoteId(alarm.id)
            val localCanonical = canonical(alarm)
            val localPayload = alarmPayload(alarm)
            val snapshot = snapshots[remoteId]

            if (snapshot != null && localCanonical == snapshot) {
                mapping[remoteId] = alarm.id
                continue
            }

            val expected = versions[remoteId] ?: 0L
            val committed = try {
                val response = api.putAlarm(
                    auth(),
                    remoteId,
                    CloudAlarmWriteRequest(
                        payload = localPayload,
                        expectedVersion = expected
                    )
                )
                CloudCommittedAlarm(response.payload, response.version, response.updatedAt)
            } catch (e: HttpException) {
                if (e.code() != 409) throw e

                val latest = fetchRemoteItem(remoteId)
                    ?: throw IllegalStateException("remote_alarm_missing")

                if (expected == 0L) {
                    // A zero expected version means we do not have a committed
                    // cloud version locally. Adopt an already-created matching
                    // row (crash recovery), otherwise always create under a
                    // fresh id. In particular, never turn a new local alarm
                    // into a delete just because an old tombstone uses the same
                    // deterministic id.
                    if (latest.deletedAt == null &&
                        canonicalPayload(latest.payload) == localCanonical) {
                        CloudCommittedAlarm(latest.payload, latest.version, latest.updatedAt)
                    } else {
                        remoteId = UUID.randomUUID().toString()
                        val response = api.putAlarm(
                            auth(),
                            remoteId,
                            CloudAlarmWriteRequest(
                                payload = localPayload,
                                expectedVersion = 0L
                            )
                        )
                        CloudCommittedAlarm(response.payload, response.version, response.updatedAt)
                    }
                } else if (latest.deletedAt != null) {
                    // Remote deletion wins. Remove the stale Android copy so it
                    // cannot be resurrected by the next watchdog pass.
                    repository.deleteById(alarm.id)
                    mapping.remove(remoteId)
                    snapshots.remove(remoteId)
                    versions.remove(remoteId)
                    continue
                } else {
                    // Three-way merge preserves local-only and remote-only
                    // fields. An exact same-field conflict keeps the current
                    // cloud value instead of blindly overwriting it.
                    val mergedPayload = mergePayload(
                        baseJson = snapshots[remoteId],
                        localPayload = localPayload,
                        remotePayload = canonicalPayloadMap(latest.payload)
                    )

                    val mergedResponse = try {
                        api.putAlarm(
                            auth(),
                            remoteId,
                            CloudAlarmWriteRequest(
                                payload = mergedPayload,
                                expectedVersion = latest.version
                            )
                        )
                    } catch (retryConflict: HttpException) {
                        if (retryConflict.code() != 409) throw retryConflict
                        val newest = fetchRemoteItem(remoteId)
                            ?: throw IllegalStateException("remote_alarm_missing")

                        if (newest.deletedAt != null) {
                            repository.deleteById(alarm.id)
                            mapping.remove(remoteId)
                            snapshots.remove(remoteId)
                            versions.remove(remoteId)
                            continue
                        }

                        // A second concurrent writer won. Preserve that newest
                        // cloud state rather than retrying stale Android data.
                        val restored = saveRemoteCopy(alarm.id, newest)
                        mapping[remoteId] = restored.id
                        snapshots[remoteId] = canonical(restored)
                        versions[remoteId] = newest.version
                        onUpdatedAt(newest.updatedAt)
                        persistMetadata(mapping, snapshots, versions)
                        continue
                    }

                    CloudCommittedAlarm(
                        mergedResponse.payload,
                        mergedResponse.version,
                        mergedResponse.updatedAt
                    )
                }
            }

            val committedAlarm = decodePayload(committed.payload)
                ?: throw IllegalStateException("alarm_payload_invalid")

            val localCommitted = committedAlarm.copy(
                id = alarm.id,
                nextTriggerTime = 0L
            )
            if (canonical(localCommitted) != localCanonical) {
                repository.update(localCommitted)
                applySchedule(localCommitted)
            }

            mapping[remoteId] = alarm.id
            snapshots[remoteId] = canonical(localCommitted)
            versions[remoteId] = committed.version
            onUpdatedAt(committed.updatedAt)
            persistMetadata(mapping, snapshots, versions)
        }
    }

    private suspend fun saveRemoteCopy(localId: Long, item: CloudAlarmDto): Alarm {
        val remoteAlarm = decodeAlarm(item)
            ?: throw IllegalStateException("remote_alarm_invalid")

        val existing = repository.getById(localId)
        val restoredId = if (existing != null) {
            repository.update(remoteAlarm.copy(id = localId, nextTriggerTime = 0L))
            localId
        } else {
            repository.save(remoteAlarm.copy(id = 0L, nextTriggerTime = 0L))
        }

        val restored = repository.getById(restoredId)
            ?: remoteAlarm.copy(id = restoredId, nextTriggerTime = 0L)
        applySchedule(restored)
        return restored
    }

    private fun mergePayload(
        baseJson: String?,
        localPayload: Map<String, Any?>,
        remotePayload: Map<String, Any?>
    ): Map<String, Any?> {
        val base = baseJson?.let {
            runCatching { mapAdapter.fromJson(it) ?: emptyMap() }.getOrNull()
        } ?: emptyMap()

        val keys = (base.keys + localPayload.keys + remotePayload.keys).toSet()
        val merged = linkedMapOf<String, Any?>()

        for (key in keys) {
            val hasBase = base.containsKey(key)
            val hasLocal = localPayload.containsKey(key)
            val hasRemote = remotePayload.containsKey(key)

            val baseValue = base[key]
            val localValue = localPayload[key]
            val remoteValue = remotePayload[key]

            val localChanged = hasLocal != hasBase ||
                (hasLocal && hasBase && !sameValue(localValue, baseValue))
            val remoteChanged = hasRemote != hasBase ||
                (hasRemote && hasBase && !sameValue(remoteValue, baseValue))

            val include: Boolean
            val value: Any?

            when {
                localChanged && !remoteChanged -> {
                    include = hasLocal
                    value = localValue
                }
                !localChanged && remoteChanged -> {
                    include = hasRemote
                    value = remoteValue
                }
                localChanged && remoteChanged && sameValue(localValue, remoteValue) -> {
                    include = hasLocal
                    value = localValue
                }
                localChanged && remoteChanged -> {
                    include = hasRemote
                    value = remoteValue
                }
                hasRemote -> {
                    include = true
                    value = remoteValue
                }
                hasLocal -> {
                    include = true
                    value = localValue
                }
                else -> {
                    include = false
                    value = null
                }
            }

            if (include) merged[key] = value
        }

        return merged
    }

    private fun sameValue(a: Any?, b: Any?): Boolean =
        runCatching { anyAdapter.toJson(a) == anyAdapter.toJson(b) }
            .getOrElse { a == b }

    private fun canonicalPayload(payload: Map<String, Any?>): String? =
        decodePayload(payload)?.let { canonical(it) }

    private fun canonicalPayloadMap(payload: Map<String, Any?>): Map<String, Any?> =
        decodePayload(payload)?.let { alarmPayload(it) } ?: payload

    private fun decodePayload(payload: Map<String, Any?>): Alarm? =
        runCatching { alarmAdapter.fromJson(mapToJson(payload)) }.getOrNull()

    private fun decodeAlarm(item: CloudAlarmDto): Alarm? =
        decodePayload(item.payload)

    private fun alarmPayload(alarm: Alarm): Map<String, Any?> =
        mapAdapter.fromJson(canonical(alarm)) ?: emptyMap()

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

    private suspend fun fetchRemoteItem(remoteId: String): CloudAlarmDto? {
        val remote = api.getAlarms(auth(), EPOCH)
        return remote.alarms.firstOrNull { it.id == remoteId }
    }

    private fun stableRemoteId(localId: Long): String {
        val seed = "\${prefs.getDeviceId()}:$localId"
        return UUID.nameUUIDFromBytes(seed.toByteArray(StandardCharsets.UTF_8)).toString()
    }

    private fun persistMetadata(
        mapping: Map<String, Long>,
        snapshots: Map<String, String>,
        versions: Map<String, Long>
    ) {
        prefs.setMapping(mapping)
        prefs.setSnapshots(snapshots)
        prefs.setVersions(versions)
    }

    private fun mapToJson(payload: Map<String, Any?>): String =
        mapAdapter.toJson(payload)

    private fun maxTimestamp(a: String, b: String): String =
        if (a >= b) a else b

    private fun auth(): String = "Bearer " + prefs.getToken()

    private data class CloudCommittedAlarm(
        val payload: Map<String, Any?>,
        val version: Long,
        val updatedAt: String
    )

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
