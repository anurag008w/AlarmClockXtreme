package com.sysadmindoc.alarmclock.data.cloud

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import com.sysadmindoc.alarmclock.domain.NextAlarmCalculator
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class CloudSyncManagerTest {
    private lateinit var context: Context
    private lateinit var api: CloudApi
    private lateinit var repository: AlarmRepository
    private lateinit var scheduler: AlarmScheduler
    private lateinit var calculator: NextAlarmCalculator
    private lateinit var prefs: CloudPreferences
    private lateinit var manager: CloudSyncManager
    private lateinit var pushTokens: PushTokenProvider
    private val stamp = "2026-10-06T07:00:00.000000Z"

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("cloud_sync", Context.MODE_PRIVATE).edit().clear().commit()
        api = mockk()
        repository = mockk(relaxed = true)
        scheduler = mockk(relaxed = true)
        calculator = mockk(relaxed = true)
        prefs = CloudPreferences(context)
        prefs.saveSession("test-token", "test@example.invalid")
        coEvery { api.getSettings(any()) } returns CloudSettingsResponse(emptyMap(), 1)
        coEvery { api.putSettings(any(), any()) } returns CloudSettingsResponse(emptyMap(), 1)
        coEvery { api.getUtilities(any(),any()) } returns CloudUtilitiesResponse(System.currentTimeMillis())
        coEvery { api.putUtilitySnapshot(any(),any(),any()) } returns emptyMap()
        coEvery { api.putDashboardSnapshot(any(),any(),any()) } returns emptyMap()
        pushTokens = mockk()
        coEvery { pushTokens.currentToken() } returns ""
        coEvery { api.getAlarms(any(), any()) } returns CloudAlarmListResponse(emptyList(), stamp)
        coEvery { api.registerDevice(any(), any()) } returns emptyMap()
        manager = CloudSyncManager(context, api, Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build(), repository, scheduler, calculator, PreferencesManager(context), mockk(relaxed = true), mockk(relaxed = true), pushTokens)
    }
    @After fun cleanup() { unmockkAll() }

    @Test fun pushTokenIsRegisteredOnceThenOnlyWhenItChanges() = runTest {
        val calls = mutableListOf<String>()
        coEvery { api.registerDevice(any(), any()) } coAnswers { calls.add(secondArg<CloudDeviceRequest>().pushToken); emptyMap() }
        coEvery { pushTokens.currentToken() } returns "token-one-aaaaaaaaaaaaaaaaaaaa"
        val r1 = manager.syncNow()
        assertTrue("sync1 failed: ${r1.exceptionOrNull()}", r1.isSuccess)
        val r2 = manager.syncNow()
        assertTrue("sync2 failed: ${r2.exceptionOrNull()}", r2.isSuccess)
        assertEquals("calls after two syncs: $calls saved=${prefs.getRegisteredPushToken()} at=${prefs.getPushRegisteredAt()} now=${System.currentTimeMillis()}", listOf("token-one-aaaaaaaaaaaaaaaaaaaa"), calls)
        assertEquals("token-one-aaaaaaaaaaaaaaaaaaaa", prefs.getRegisteredPushToken())
        coEvery { pushTokens.currentToken() } returns "token-two-bbbbbbbbbbbbbbbbbbbb"
        assertTrue(manager.syncNow().isSuccess)
        assertEquals("calls: $calls", listOf("token-one-aaaaaaaaaaaaaaaaaaaa", "token-two-bbbbbbbbbbbbbbbbbbbb"), calls)
    }

    @Test fun staleRegistrationIsRefreshedAfterADay() = runTest {
        coEvery { pushTokens.currentToken() } returns "token-one-aaaaaaaaaaaaaaaaaaaa"
        prefs.savePushRegistration("token-one-aaaaaaaaaaaaaaaaaaaa", System.currentTimeMillis() - 25L*60*60*1000)
        assertTrue(manager.syncNow().isSuccess)
        coVerify(exactly=1) { api.registerDevice(any(), any()) }
    }

    @Test fun noPushTokenMeansPollOnlyAndNoRegistrationCall() = runTest {
        assertTrue(manager.syncNow().isSuccess)
        coVerify(exactly=0) { api.registerDevice(any(), any()) }
    }

    @Test fun failedTokenRegistrationDoesNotFailTheSyncAndRetriesNextTime() = runTest {
        coEvery { pushTokens.currentToken() } returns "token-one-aaaaaaaaaaaaaaaaaaaa"
        coEvery { api.registerDevice(any(), any()) } throws java.io.IOException("offline")
        val r1 = manager.syncNow()
        assertTrue("sync1 failed: ${r1.exceptionOrNull()}", r1.isSuccess)
        assertEquals("", prefs.getRegisteredPushToken())
        coEvery { api.registerDevice(any(), any()) } returns emptyMap()
        val r2 = manager.syncNow()
        assertTrue("sync2 failed: ${r2.exceptionOrNull()}", r2.isSuccess)
        assertEquals("saved=${prefs.getRegisteredPushToken()} at=${prefs.getPushRegisteredAt()}", "token-one-aaaaaaaaaaaaaaaaaaaa", prefs.getRegisteredPushToken())
    }

    @Test fun loggingOutForgetsRegisteredToken() = runTest {
        prefs.savePushRegistration("token-one-aaaaaaaaaaaaaaaaaaaa", 1L)
        prefs.clearSession()
        assertEquals("", prefs.getRegisteredPushToken())
        assertEquals(0L, prefs.getPushRegisteredAt())
    }

    @Test fun remoteDeleteCancelsNativeSchedule() = runTest {
        val alarm = Alarm(id=7, hour=7, minute=0, isEnabled=false)
        prefs.setMapping(mapOf("remote" to 7L))
        // Push phase needs a matching canonical snapshot. Populate metadata
        // through a preceding remote read rather than depending on private helpers.
        coEvery { repository.getAll() } returns listOf(alarm)
        coEvery { repository.getById(7L) } returns alarm
        coEvery { repository.deleteExactAlarmIfUnchanged(alarm) } returns true
        coEvery { api.putAlarm(any(), any(), any()) } returns CloudAlarmWriteResponse("remote", mapOf("hour" to 7, "minute" to 0, "isEnabled" to false), 1, stamp)
        coEvery { api.getAlarms(any(), any()) } returns CloudAlarmListResponse(listOf(CloudAlarmDto("remote", emptyMap(), 2, stamp, stamp)), stamp)
        assertTrue(manager.syncNow().isSuccess)
        verify(atLeast=1) { scheduler.cancel(7L) }
        coVerify(exactly=1) { repository.deleteExactAlarmIfUnchanged(alarm) }
        assertFalse(prefs.getMapping().containsKey("remote"))
    }

    @Test fun bootstrapAppliesRemoteSoundInsteadOfMarkingLocalSoundSynced() = runTest {
        val local = Alarm(id=7, createdAt=1000L, hour=7, minute=0, label="Wake", ringtoneUri="content://old", isEnabled=false)
        val payload = mapOf<String,Any?>("createdAt" to 1000L, "hour" to 7, "minute" to 0, "label" to "Wake", "ringtoneUri" to "content://new", "isEnabled" to false)
        val remote = CloudAlarmDto("remote", payload, 3, stamp)
        coEvery { repository.getAll() } returns listOf(local)
        coEvery { repository.getById(7L) } returnsMany listOf(local,local.copy(ringtoneUri="content://new"))
        coEvery { repository.updateExactAlarmIfUnchanged(any(),any()) } returns true
        coEvery { api.getAlarms(any(), any()) } returns CloudAlarmListResponse(listOf(remote), stamp)
        assertTrue(manager.syncNow().isSuccess)
        coVerify { repository.updateExactAlarmIfUnchanged(local,match { it.id==7L && it.ringtoneUri=="content://new" }) }
        assertEquals(3L,prefs.getVersions()["remote"])
        coVerify(exactly=0) { api.putAlarm(any(),any(),any()) }
    }
    @Test fun logoutWaitsForInflightSyncSession() = runTest {
        val started=kotlinx.coroutines.CompletableDeferred<Unit>()
        val release=kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { api.getAlarms(any(),any()) } coAnswers { started.complete(Unit);release.await();CloudAlarmListResponse(emptyList(),stamp) }
        val syncing=async { manager.syncNow() }
        started.await()
        val loggingOut=async { manager.logout() }
        kotlinx.coroutines.yield()
        assertTrue(manager.isLoggedIn())
        release.complete(Unit);syncing.await();loggingOut.await()
        assertFalse(manager.isLoggedIn())
    }
}
