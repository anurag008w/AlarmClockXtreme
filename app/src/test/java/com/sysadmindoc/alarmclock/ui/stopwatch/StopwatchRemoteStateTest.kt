package com.sysadmindoc.alarmclock.ui.stopwatch
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.cloud.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30],application=Application::class)
class StopwatchRemoteStateTest {
 @Test fun staleNativeButtonCannotOverwriteRemotePauseOrReset() {
  val context=ApplicationProvider.getApplicationContext<Context>()
  context.getSharedPreferences("stopwatch_state",Context.MODE_PRIVATE).edit().clear().commit()
  context.getSharedPreferences("cloud_utility_journal",Context.MODE_PRIVATE).edit().clear().commit()
  val model=StopwatchViewModel(context);model.start()
  val controller=CloudUtilityController(context)
  assertEquals("applied",controller.apply(CloudUtilityCommand("p","remote-pause-123","stopwatch","pause")).first)
  model.pause() // must refresh persisted PAUSED state, not add old running delta
  assertEquals("PAUSED",CloudUtilityController.stopwatchSnapshot(context)["state"])
  assertEquals("applied",controller.apply(CloudUtilityCommand("p","remote-reset-123","stopwatch","reset")).first)
  model.lap()
  assertEquals("IDLE",CloudUtilityController.stopwatchSnapshot(context)["state"])
  assertTrue((CloudUtilityController.stopwatchSnapshot(context)["laps"] as List<*>).isEmpty())
 }
 @Test fun undoCannotOverwriteACommandAppliedAfterNativeReset() {
  val context=ApplicationProvider.getApplicationContext<Context>()
  context.getSharedPreferences("stopwatch_state",Context.MODE_PRIVATE).edit().clear().commit()
  context.getSharedPreferences("cloud_utility_journal",Context.MODE_PRIVATE).edit().clear().commit()
  val model=StopwatchViewModel(context);model.start();model.lap();model.reset()
  val controller=CloudUtilityController(context)
  assertEquals("applied",controller.apply(CloudUtilityCommand("p","remote-start-789","stopwatch","start")).first)
  model.undoReset()
  assertEquals("RUNNING",CloudUtilityController.stopwatchSnapshot(context)["state"])
  assertTrue((CloudUtilityController.stopwatchSnapshot(context)["laps"] as List<*>).isEmpty())
 }
}
