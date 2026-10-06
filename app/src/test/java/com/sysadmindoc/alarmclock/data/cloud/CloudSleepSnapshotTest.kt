package com.sysadmindoc.alarmclock.data.cloud
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.health.*
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.repository.*
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30],application=Application::class)
class CloudSleepSnapshotTest {
 @Test fun disabledHealthDoesNotReadProviderOrStartSensors() = runTest {
  val context=ApplicationProvider.getApplicationContext<Context>()
  val health=mockk<HealthConnectSleepRepository>()
  val act=mockk<ActigraphyRepository>();coEvery { act.getRecent(10) } returns emptyList()
  val snores=mockk<SnoreEventRepository>();coEvery { snores.getRecent(50) } returns emptyList()
  val tags=mockk<PreSleepTagRepository>();every { tags.observeForDate(any()) } returns flowOf(emptyList());coEvery { tags.readCorrelations(any(),any()) } returns emptyList()
  val data=CloudSleepSnapshot(context,health,act,snores,tags).build(AppSettings())
  coVerify(exactly=0) { health.readRecentSleepSummary(any(),any()) }
  assertFalse((data["health"] as Map<*,*>)["enabled"] as Boolean)
  assertEquals(false,(data["sonar"] as Map<*,*>)["active"])
  assertFalse(data.containsKey("audio"))
 }
 @Test fun enabledHealthReadsExistingStagesWithoutRequestingPermission() = runTest {
  val context=ApplicationProvider.getApplicationContext<Context>()
  val health=mockk<HealthConnectSleepRepository>()
  coEvery { health.readRecentSleepSummary(any(),any()) } returns HealthConnectSleepSummary(availability=HealthConnectAvailability.AVAILABLE,permissionGranted=true,sessionsRead=1,recentSessions=listOf(HealthConnectSleepSession(1000,2000,1,deepStageMinutes=1)))
  val act=mockk<ActigraphyRepository>();coEvery { act.getRecent(10) } returns emptyList()
  val snores=mockk<SnoreEventRepository>();coEvery { snores.getRecent(50) } returns emptyList()
  val tags=mockk<PreSleepTagRepository>();every { tags.observeForDate(any()) } returns flowOf(emptyList());coEvery { tags.readCorrelations(any(),any()) } returns emptyList()
  val data=CloudSleepSnapshot(context,health,act,snores,tags).build(AppSettings(healthConnectEnabled=true))
  val sessions=(data["health"] as Map<*,*>)["sessions"] as List<*>
  assertEquals(1L,(sessions.single() as Map<*,*>)["deepStageMinutes"])
  verify(exactly=0) { health.createPermissionRequestContract() }
 }
}
