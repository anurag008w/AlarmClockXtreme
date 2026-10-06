package com.sysadmindoc.alarmclock.data.cloud
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.repository.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30],application=Application::class)
class CloudDashboardSnapshotTest {
 @Test fun snapshotKeepsPermissionStatusAndDoesNotGuessLiveLocationOrNextTrigger() = runTest {
  val context=ApplicationProvider.getApplicationContext<Context>()
  val alarms=mockk<AlarmRepository>();coEvery { alarms.getNextAlarm() } returns null;coEvery { alarms.getAll() } returns emptyList()
  val history=mockk<AlarmEventRepository>();coEvery { history.getStats() } returns AlarmStats(totalDismissed=3);coEvery { history.getRecent(50) } returns emptyList()
  val data=CloudDashboardSnapshot.build(context,AppSettings(),alarms,history)
  assertNull(data["location"]);assertNull(data["nextAlarm"])
  assertEquals("permission_required",data["calendarStatus"])
  assertEquals(3,(data["stats"] as Map<*,*>)["totalDismissed"])
  assertFalse(data.containsKey("sleep"));assertFalse(data.containsKey("token"))
 }
 @Test fun savedWeatherCoordinatesAreLabeledNotLiveGps() = runTest {
  val context=ApplicationProvider.getApplicationContext<Context>()
  val alarms=mockk<AlarmRepository>();coEvery { alarms.getNextAlarm() } returns null;coEvery { alarms.getAll() } returns emptyList()
  val history=mockk<AlarmEventRepository>();coEvery { history.getStats() } returns AlarmStats();coEvery { history.getRecent(50) } returns emptyList()
  val settings=AppSettings(locationName="Pune",lastKnownLatitude=18.5,lastKnownLongitude=73.8,showCalendarOnDashboard=false)
  val data=CloudDashboardSnapshot.build(context,settings,alarms,history)
  assertEquals("disabled",data["calendarStatus"])
  assertEquals("saved_weather_location",(data["location"] as Map<*,*>)["kind"])
 }
 @Test fun perAlarmStatsCarryCloudIdentityAndMissingReferenceReadiness() = runTest {
  val context=ApplicationProvider.getApplicationContext<Context>()
  val alarm=com.sysadmindoc.alarmclock.data.model.Alarm(id=7,hour=7,minute=0,challengeType="PHOTO_MATCH",photoMatchUri="")
  val alarms=mockk<AlarmRepository>();coEvery { alarms.getNextAlarm() } returns null;coEvery { alarms.getAll() } returns listOf(alarm)
  val history=mockk<AlarmEventRepository>();coEvery { history.getStats() } returns AlarmStats();coEvery { history.getRecent(50) } returns emptyList();coEvery { history.getPerAlarmStats(7,30) } returns AlarmEventRepository.PerAlarmStats(4,1.5,12,1)
  val data=CloudDashboardSnapshot.build(context,AppSettings(),alarms,history,mapOf("cloud-a" to 7L))
  val detail=((data["alarmDetails"] as List<*>).single() as Map<*,*>)
  assertEquals("cloud-a",detail["cloudAlarmId"]);assertEquals(4,detail["fireCount"])
  assertTrue(detail["blocksSave"] as Boolean)
 }
}
