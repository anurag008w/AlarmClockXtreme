package com.sysadmindoc.alarmclock.data.cloud
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.repository.*
import com.sysadmindoc.alarmclock.domain.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30],application=Application::class)
class CloudAlarmCommandControllerTest {
 @Test fun reviewedOccurrenceSkipsOnceAndRejectsChangedTrigger() = runTest {
  val context=ApplicationProvider.getApplicationContext<Context>()
  context.getSharedPreferences("cloud_alarm_command_journal",Context.MODE_PRIVATE).edit().clear().commit()
  val expected=System.currentTimeMillis()+3600000
  val alarm=Alarm(id=7,hour=8,repeatDays=setOf(java.time.DayOfWeek.MONDAY),nextTriggerTime=expected)
  val repo=mockk<AlarmRepository>();val history=mockk<AlarmEventRepository>(relaxed=true);val scheduler=mockk<AlarmScheduler>(relaxed=true);val calculator=mockk<NextAlarmCalculator>()
  coEvery { repo.getById(7) } returnsMany listOf(alarm,alarm,alarm.copy(nextTriggerTime=expected+86400000),alarm.copy(nextTriggerTime=expected+86400000),alarm)
  every { calculator.calculate(any(),any()) } returns expected+86400000
  coEvery { repo.advanceExactAlarmIfUnchanged(alarm,expected+86400000) } returns true
  coEvery { scheduler.scheduleAt(any(),expected+86400000,true) } returns true
  val controller=CloudAlarmCommandController(context,repo,history,scheduler,calculator)
  val command=CloudUtilityCommand("phone","skip-12345","alarm","skip-next",mapOf("cloudAlarmId" to "a","expectedNextTriggerTime" to expected))
  assertEquals("applied",controller.apply(command,mapOf("a" to 7L)).first)
  assertEquals("applied",controller.apply(command,mapOf("a" to 7L)).first)
  coVerify(exactly=1) { scheduler.scheduleAt(any(),any(),any()) }
  assertEquals("rejected",controller.apply(command.copy(commandId="skip-stale-123",payload=command.payload+mapOf("expectedNextTriggerTime" to expected+1)),mapOf("a" to 7L)).first)
  coVerify(exactly=1) { scheduler.scheduleAt(any(),any(),any()) }
 }
}
