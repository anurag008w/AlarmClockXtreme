package com.sysadmindoc.alarmclock.data.cloud
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.ui.timer.TimerStore
import com.sysadmindoc.alarmclock.ui.timer.TimerState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30],application=Application::class)
class CloudUtilityControllerTest {
 @Test fun timerCommandsUsePhoneClockAndAreNotDuplicated() {
  val context=ApplicationProvider.getApplicationContext<Context>()
  context.getSharedPreferences("cloud_utility_journal",Context.MODE_PRIVATE).edit().clear().commit()
  val store=TimerStore(context);store.replace(emptyList())
  val controller=CloudUtilityController(context)
  val start=CloudUtilityCommand("phone","start-123","timer","start",mapOf("seconds" to 60.0,"label" to "Test"))
  assertEquals("applied",controller.apply(start).first)
  assertEquals("applied",controller.apply(start).first)
  assertEquals(1,store.loadRecords().size)
  val id=store.loadRecords().single().id
  assertEquals("applied",controller.apply(CloudUtilityCommand("phone","pause-123","timer","pause",mapOf("timerId" to id))).first)
  assertEquals(TimerState.PAUSED,store.loadRecords().single().state)
  assertEquals("applied",controller.apply(CloudUtilityCommand("phone","resume-123","timer","resume",mapOf("timerId" to id))).first)
  assertEquals(TimerState.RUNNING,store.loadRecords().single().state)
  assertEquals("applied",controller.apply(CloudUtilityCommand("phone","stop-123","timer","stop",mapOf("timerId" to id))).first)
  assertTrue(store.loadRecords().isEmpty())
 }
 @Test fun fullJournalRejectsNewCommandWithoutBlockingSyncOrReplayingEffects() {
  val context=ApplicationProvider.getApplicationContext<Context>()
  val journal=context.getSharedPreferences("cloud_utility_journal",Context.MODE_PRIVATE)
  val editor=journal.edit().clear()
  for(i in 0 until 10000) editor.putString("old-$i","applied:done")
  editor.commit()
  val result=CloudUtilityController(context).apply(CloudUtilityCommand("phone","new-command-123","stopwatch","start"))
  assertEquals("rejected",result.first)
  assertEquals("utility_journal_full",result.second)
  journal.edit().clear().commit()
 }
 @Test fun interruptedClaimDoesNotReplayNativeEffect() {
  val context=ApplicationProvider.getApplicationContext<Context>()
  context.getSharedPreferences("cloud_utility_journal",Context.MODE_PRIVATE).edit().putString("crashed-123","claimed").commit()
  val result=CloudUtilityController(context).apply(CloudUtilityCommand("phone","crashed-123","timer","start",mapOf("seconds" to 60)))
  assertEquals("rejected",result.first)
  assertEquals("interrupted_check_phone",result.second)
 }
 @Test fun stopwatchCommandsUpdateNativeRunAndLaps() {
  val context=ApplicationProvider.getApplicationContext<Context>()
  context.getSharedPreferences("stopwatch_state",Context.MODE_PRIVATE).edit().clear().commit()
  context.getSharedPreferences("cloud_utility_journal",Context.MODE_PRIVATE).edit().clear().commit()
  val controller=CloudUtilityController(context)
  fun cmd(id:String,action:String)=controller.apply(CloudUtilityCommand("phone",id,"stopwatch",action))
  assertEquals("applied",cmd("sw-start-123","start").first)
  assertEquals("RUNNING",CloudUtilityController.stopwatchSnapshot(context)["state"])
  assertEquals("applied",cmd("sw-lap-123","lap").first)
  assertEquals("applied",cmd("sw-lap-123","lap").first)
  assertEquals(1,(CloudUtilityController.stopwatchSnapshot(context)["laps"] as List<*>).size)
  assertEquals("applied",cmd("sw-pause-123","pause").first)
  assertEquals("PAUSED",CloudUtilityController.stopwatchSnapshot(context)["state"])
  assertEquals("applied",cmd("sw-resume-123","resume").first)
  assertEquals("applied",cmd("sw-reset-123","reset").first)
  assertEquals("IDLE",CloudUtilityController.stopwatchSnapshot(context)["state"])
  assertEquals(0,(CloudUtilityController.stopwatchSnapshot(context)["laps"] as List<*>).size)
 }
}
