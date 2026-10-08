package com.sysadmindoc.alarmclock.data.cloud

import android.content.Context
import com.sysadmindoc.alarmclock.BuildConfig
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import com.sysadmindoc.alarmclock.domain.NextAlarmCalculator
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.map
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.receiver.BedtimeReceiver
import java.time.ZonedDateTime
import java.time.LocalTime
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
    private val calculator: NextAlarmCalculator,
    private val preferencesManager: PreferencesManager,
    private val eventRepository: com.sysadmindoc.alarmclock.data.repository.AlarmEventRepository,
    private val sleepSnapshot: CloudSleepSnapshot,
    private val pushTokens: PushTokenProvider
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
        mutex.withLock {
            prefs.saveSession(response.token,response.user.email)
            runCatching { registerDevice() }
        }
        runCatching { syncNow(forceFull = true) }
        response.user.email
    }

    suspend fun register(email: String, password: String): Result<String> = runCatching {
        val response = api.register(CloudAuthRequest(email.trim(), password))
        mutex.withLock {
            prefs.saveSession(response.token,response.user.email)
            runCatching { registerDevice() }
        }
        runCatching { syncNow(forceFull = true) }
        response.user.email
    }

    suspend fun logout() {
        mutex.withLock { prefs.clearSession() }
    }

    suspend fun sendAiCommand(command: String): Result<String> = runCatching {
        require(isLoggedIn()) { "not_logged_in" }
        val response = mutex.withLock {
            require(isLoggedIn()) { "not_logged_in" }
            api.aiCommand(auth(), CloudAiRequest(command.trim()))
        }
        syncNow(forceFull = false).getOrThrow()
        response.message ?: "done"
    }

    suspend fun syncNow(forceFull: Boolean = false): Result<Unit> = runCatching {
        mutex.withLock {
            if (!isLoggedIn()) return@withLock

            val settingsResult = runCatching { syncSettings() }.onFailure {
                if (it is CancellationException) throw it
            }

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
                        if (local != null) {
                            if(!repository.deleteExactAlarmIfUnchanged(local))continue
                            scheduler.cancel(local.id)
                        }
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
                    if(!repository.updateExactAlarmIfUnchanged(local,updated))continue
                    val latest=repository.getById(local.id)
                    if(latest==null)scheduler.cancel(local.id) else applySchedule(latest)
                    snapshots[item.id] = canonical(updated)
                    versions[item.id] = item.version
                }
            } finally {
                applyingRemote = false
            }

            persistMetadata(mapping, snapshots, versions)
            prefs.setCursor(cursor)
            settingsResult.getOrThrow()
            syncUtilities()
            syncDashboard()
            runCatching { registerPushTokenIfNeeded() }.onFailure {
                if (it is CancellationException) throw it
            }
        }
    }

    @OptIn(FlowPreview::class)
    /**
     * Lightweight in-process watchdog for Android create/edit/delete.
     * Room invalidation wakes this collector; the short debounce coalesces
     * rapid editor writes without a polling loop or wake lock.
     */
    suspend fun observeLocalChanges() {
        merge(repository.observeAll().distinctUntilChanged().map { Unit }, preferencesManager.settings.distinctUntilChanged().map { Unit }, observeUtilityChanges(), eventRepository.observeRecent(50).map { Unit })
            .debounce(300L)
            .conflate()
            .collect {
                if (!applyingRemote && isLoggedIn()) {
                    val result = syncNow()
                    if (result.isFailure) CloudSyncWorker.enqueueImmediate(context)
                }
            }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun observeUtilityChanges(): kotlinx.coroutines.flow.Flow<Unit> = kotlinx.coroutines.flow.callbackFlow {
        val stores = listOf("timer_state", "stopwatch_state").map { context.getSharedPreferences(it, Context.MODE_PRIVATE) }
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(Unit) }
        stores.forEach { it.registerOnSharedPreferenceChangeListener(listener) }
        awaitClose { stores.forEach { it.unregisterOnSharedPreferenceChangeListener(listener) } }
    }

    private suspend fun syncSettings() {
        // Server-first on a fresh login. Never upload guessed defaults over
        // existing remote preferences when there is no base snapshot.
        val remote = try { api.getSettings(auth()) } catch (error: HttpException) {
            // Rolling deployment: an older cloud must not break existing alarm sync.
            if (error.code() == 404) return
            throw error
        }
        val local = preferencesManager.cloudSettings()
        val base = prefs.getSettingsSnapshot()?.let { mapAdapter.fromJson(it) }
        var chosen = remote
        if (remote.version == 0L || (base != null && mapAdapter.toJson(local) != mapAdapter.toJson(base))) {
            // A key the user changed on this device since the last sync always wins over
            // the cloud copy: a local toggle must never be reverted by stale remote data.
            fun mergeOnto(remotePayload: Map<String, Any?>): Map<String, Any?> {
                val merged = remotePayload.toMutableMap()
                for ((key, value) in local) {
                    if (base == null) {
                        if (!remotePayload.containsKey(key)) merged[key] = value
                    } else if (!sameValue(value, base[key])) {
                        merged[key] = value
                    } else if (!remotePayload.containsKey(key)) {
                        merged[key] = value
                    }
                }
                return merged
            }
            chosen = try {
                api.putSettings(auth(), CloudAlarmWriteRequest(mergeOnto(remote.payload), remote.version))
            } catch (error: HttpException) {
                if (error.code() != 409) throw error
                // Concurrent cloud change: re-merge our local edits onto the fresh copy and retry
                // once. A second conflict aborts this round (local state is left untouched and
                // the next sync retries) instead of overwriting the local edit.
                val fresh = api.getSettings(auth())
                api.putSettings(auth(), CloudAlarmWriteRequest(mergeOnto(fresh.payload), fresh.version))
            }
        }
        // The network calls above take time. Re-read the live settings and never write cloud
        // values over a key the user changed while the sync was in flight.
        val latest = preferencesManager.cloudSettings()
        val changedMeanwhile = latest.keys.filter { !sameValue(latest[it], local[it]) }.toSet()
        val toApply = chosen.payload.filterKeys { it !in changedMeanwhile }
        applyingRemote = true
        try {
            if (toApply.any { (key, value) -> !sameValue(latest[key], value) }) {
                preferencesManager.applyCloudSettings(toApply)
            }
            val applied = preferencesManager.cloudSettings()
            if (CloudSettingsEffects.alarmsChanged(local, applied)) {
                scheduler.rescheduleAll(forceRecalculate = true)
            }
            if (CloudSettingsEffects.bedtimeChanged(local, applied)) {
                val settings = preferencesManager.getCurrentSettings()
                context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit()
                    .putBoolean("bedtime_reschedule", settings.bedtimeEnabled).apply()
                if (settings.bedtimeEnabled) {
                    val now = ZonedDateTime.now()
                    var reminder = now.with(LocalTime.of(settings.bedtimeHour, settings.bedtimeMinute))
                        .minusMinutes(settings.bedtimeReminderMinutes.toLong())
                    if (!reminder.isAfter(now)) reminder = reminder.plusDays(1)
                    val at = maxOf(reminder.toInstant().toEpochMilli(), settings.bedtimeStayUpLateUntilMillis)
                    BedtimeReceiver.schedule(context, at)
                } else BedtimeReceiver.cancelScheduled(context)
            }
            // Keys changed mid-sync keep the cloud value as their base so the next sync
            // sees them as local edits and uploads them.
            val baseSnapshot = applied.toMutableMap().also { m ->
                changedMeanwhile.forEach { k -> if (chosen.payload.containsKey(k)) m[k] = chosen.payload[k] }
            }
            prefs.saveSettingsMetadata(mapAdapter.toJson(baseSnapshot), chosen.version)
        } finally { applyingRemote = false }
        if (changedMeanwhile.isNotEmpty()) CloudSyncWorker.enqueueImmediate(context)
    }

    private var lastDashboardSuccessElapsed = -1L
    private var dashboardAccount = ""
    private var dashboardSignature = ""
    private suspend fun syncDashboard() {
        val now = android.os.SystemClock.elapsedRealtime()
        val account = prefs.getEmail()
        val snapshotAuth = auth()
        if (dashboardAccount == account && lastDashboardSuccessElapsed >= 0 && now - lastDashboardSuccessElapsed in 0..59_999) return
        val settings = preferencesManager.getCurrentSettings()
        val payload = CloudDashboardSnapshot.build(context, settings, repository, eventRepository, prefs.getMapping()) + ("sleep" to sleepSnapshot.build(settings))
        if (!isLoggedIn() || account != prefs.getEmail() || snapshotAuth != auth()) return
        val signature = mapAdapter.toJson(payload - "phoneObservedMillis")
        if (dashboardAccount != account || signature != dashboardSignature || lastDashboardSuccessElapsed < 0 || now - lastDashboardSuccessElapsed >= 900_000) {
            try {
                api.putDashboardSnapshot(snapshotAuth, prefs.getDeviceId(), payload)
            } catch (error: HttpException) {
                if (error.code() != 404) throw error
                registerDevice()
                api.putDashboardSnapshot(snapshotAuth, prefs.getDeviceId(), payload)
            }
            dashboardSignature = signature
        }
        dashboardAccount = account
        lastDashboardSuccessElapsed = now
    }

    /** Only called while an activity is resumed. No background wake lock. */
    suspend fun receiveForegroundUtilities(): Result<Unit> = runCatching {
        mutex.withLock { if (isLoggedIn()) syncUtilities() }
    }

    private suspend fun syncUtilities() {
        val device = prefs.getDeviceId()
        val remote = try { api.getUtilities(auth(), device) } catch (error: HttpException) {
            if (error.code() == 404) return
            throw error
        }
        // Prune only timestamped IDs beyond server replay rejection window.
        // Use authenticated server time, never a possibly shifted phone clock.
        for(name in listOf("cloud_utility_journal","cloud_alarm_command_journal")) {
            val journal=context.getSharedPreferences(name,Context.MODE_PRIVATE)
            val stale=journal.all.keys.filter { key -> key.matches(Regex("v2-\\d{13}-[a-f0-9]{32}")) && (key.split("-")[1].toLongOrNull() ?: Long.MAX_VALUE) < remote.serverNowMillis-86400000 }
            if(stale.isNotEmpty()) { val edit=journal.edit();stale.forEach { edit.remove(it) };check(edit.commit()) }
        }
        val fetchedAtElapsed = android.os.SystemClock.elapsedRealtime()
        val controller = CloudUtilityController(context)
        for (row in remote.items.filter { it.status == "pending" }.sortedWith(compareBy<CloudUtilityRow> { it.createdMillis }.thenBy { it.id })) {
            val command = row.command ?: continue
            if (command.deviceId != device) continue
            val result = if (row.expiresMillis <= remote.serverNowMillis + (android.os.SystemClock.elapsedRealtime() - fetchedAtElapsed)) {
                "expired" to "phone_was_unavailable"
            } else if(command.kind == "alarm") CloudAlarmCommandController(context,repository,eventRepository,scheduler,calculator).apply(command,prefs.getMapping()) else controller.apply(command)
            api.acknowledgeUtility(auth(), device, mapOf("commandId" to command.commandId, "status" to result.first, "result" to result.second))
        }
        val snapshot = synchronized(NativeUtilityLock.monitor) {
        val timers = com.sysadmindoc.alarmclock.ui.timer.TimerStore(context).loadRecords().map { timer ->
            mapOf("id" to timer.id, "label" to timer.label, "state" to timer.state.name,
                "remainingMillis" to timer.remainingMillis, "totalSeconds" to timer.totalSeconds)
        }
        val stopwatch = CloudUtilityController.stopwatchSnapshot(context)
        timers to stopwatch
        }
        val (timers, stopwatch) = snapshot
        val journal = context.getSharedPreferences("cloud_utility_journal", Context.MODE_PRIVATE)
        val signature = mapAdapter.toJson(mapOf("timers" to timers.map { it - "remainingMillis" }, "stopwatch" to (stopwatch - "elapsedMillis")))
        if (journal.getString("snapshotSignature", null) != signature || remote.items.any { it.status == "pending" }) {
            api.putUtilitySnapshot(auth(), device, mapOf("timers" to timers,"stopwatch" to stopwatch))
            check(journal.edit().putString("snapshotSignature", signature).commit())
        }
    }

    private suspend fun registerDevice() {
        if (!isLoggedIn()) return
        val token = currentPushToken()
        api.registerDevice(
            auth(),
            CloudDeviceRequest(
                deviceId = prefs.getDeviceId(),
                appVersion = BuildConfig.VERSION_NAME,
                pushToken = token
            )
        )
        if (token.isNotBlank()) prefs.savePushRegistration(token, System.currentTimeMillis())
    }

    private suspend fun currentPushToken(): String =
        runCatching { pushTokens.currentToken() }
            .onFailure { if (it is CancellationException) throw it }
            .getOrDefault("")

    /**
     * Tell the server about a new or rotated push token, and refresh it once a
     * day so the server can drop tokens of devices that stopped syncing.
     */
    private suspend fun registerPushTokenIfNeeded() {
        if (!isLoggedIn()) return
        val token = currentPushToken()
        if (token.isBlank()) return
        val age = System.currentTimeMillis() - prefs.getPushRegisteredAt()
        if (token == prefs.getRegisteredPushToken() && age in 0 until PUSH_REREGISTER_MILLIS) return
        registerDevice()
    }

    private suspend fun pushLocalChanges(
        mapping: MutableMap<String, Long>,
        snapshots: MutableMap<String, String>,
        versions: MutableMap<String, Long>,
        onUpdatedAt: (String) -> Unit
    ) {
        val local = repository.getAll()
        val localIds = local.map { it.id }.toSet()

        // Bootstrap/re-login dedupe: when local Room alarms have no cloud
        // mapping yet, inspect the existing cloud dataset once and adopt an
        // exact canonical match instead of creating a second remote row.
        // This preserves intentional duplicates: one remote row is claimed
        // by at most one local alarm; additional identical local rows still
        // receive their own stable IDs.
        val mappedLocalIds = mapping.values.toSet()
        val bootstrapRemotes = if (local.any { it.id !in mappedLocalIds }) {
            api.getAlarms(auth(), EPOCH).alarms
        } else {
            emptyList()
        }
        val bootstrapMatches = bootstrapRemotes
            .asSequence()
            .filter { it.deletedAt == null }
            .filter { item ->
                val remoteCanonical = canonicalPayload(item.payload)
                remoteCanonical != null && remoteCanonical.isNotBlank()
            }
            .sortedByDescending { it.updatedAt }
            .toList()
        val claimedBootstrapRemoteIds = mutableSetOf<String>()

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
            var remoteId = inverse[alarm.id] ?: stableRemoteId(alarm)
            var localCanonical = canonical(alarm)
            var localPayload = alarmPayload(alarm)

            // When the Room row is currently unmapped (first login, restored
            // app data, or metadata cleared by an older build), claim an
            // existing identical cloud alarm before creating a new ID.
            if (inverse[alarm.id] == null) {
                val match = bootstrapMatches.firstOrNull { remote ->
                    remote.id !in mapping &&
                        remote.id !in claimedBootstrapRemoteIds &&
                        run {
                            val remoteAlarm = decodeAlarm(remote)
                            (remoteAlarm != null && sameAlarmIdentity(remoteAlarm, alarm)) ||
                                canonicalPayload(remote.payload) == localCanonical
                        }
                }
                if (match != null) {
                    remoteId = match.id
                    claimedBootstrapRemoteIds += match.id
                    mapping[remoteId] = alarm.id
                    val restored = saveRemoteCopy(alarm.id, match, alarm)
                    snapshots[remoteId] = canonical(restored)
                    versions[remoteId] = match.version
                    onUpdatedAt(match.updatedAt)
                    continue
                }
            }

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
                    check(repository.deleteExactAlarmIfUnchanged(alarm)) { "native_alarm_changed_retry_sync" }
                    scheduler.cancel(alarm.id)
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
                            check(repository.deleteExactAlarmIfUnchanged(alarm)) { "native_alarm_changed_retry_sync" }
                            scheduler.cancel(alarm.id)
                            mapping.remove(remoteId)
                            snapshots.remove(remoteId)
                            versions.remove(remoteId)
                            continue
                        }

                        // A second concurrent writer won. Preserve that newest
                        // cloud state rather than retrying stale Android data.
                        val restored = saveRemoteCopy(alarm.id, newest, alarm)
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
                if(repository.updateExactAlarmIfUnchanged(alarm,localCommitted)) {
                    val latest=repository.getById(alarm.id)
                    if(latest==null)scheduler.cancel(alarm.id) else applySchedule(latest)
                }
            }

            mapping[remoteId] = alarm.id
            snapshots[remoteId] = canonical(localCommitted)
            versions[remoteId] = committed.version
            onUpdatedAt(committed.updatedAt)
            persistMetadata(mapping, snapshots, versions)
        }
    }

    private suspend fun saveRemoteCopy(localId: Long, item: CloudAlarmDto, expected: Alarm? = null): Alarm {
        val remoteAlarm = decodeAlarm(item)
            ?: throw IllegalStateException("remote_alarm_invalid")

        val existing = repository.getById(localId)
        if(expected!=null)check(existing==expected) { "native_alarm_changed_retry_sync" }
        val restoredId = if (existing != null) {
            check(repository.updateExactAlarmIfUnchanged(existing,remoteAlarm.copy(id = localId, nextTriggerTime = 0L))) { "native_alarm_changed_retry_sync" }
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

    private fun stableRemoteId(alarm: Alarm): String {
        // createdAt is immutable alarm identity carried across devices.
        // Room ids are device-local and must never define cloud identity.
        val seed = if (alarm.createdAt > 0L) {
            "alarm-created:${alarm.createdAt}"
        } else {
            "alarm-device:${prefs.getDeviceId()}:fallback"
        }
        return UUID.nameUUIDFromBytes(seed.toByteArray(StandardCharsets.UTF_8)).toString()
    }

    private fun sameAlarmIdentity(a: Alarm, b: Alarm): Boolean =
        a.createdAt > 0L && b.createdAt > 0L && a.createdAt == b.createdAt

    private fun persistMetadata(
        mapping: Map<String, Long>,
        snapshots: Map<String, String>,
        versions: Map<String, Long>
    ) {
        prefs.saveAlarmMetadata(mapping, snapshots, versions)
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
        const val PUSH_REREGISTER_MILLIS = 24L * 60 * 60 * 1000
    }
}
