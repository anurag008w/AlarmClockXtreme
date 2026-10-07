package com.sysadmindoc.alarmclock.ui.stopwatch

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.cloud.CloudUtilityController
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class StopwatchNotificationsTest {
    @Test fun runningPauseLapResumeAndResetUsePersistedState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("stopwatch_state", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val vm = StopwatchViewModel(context)
        val manager = shadowOf(context.getSystemService(NotificationManager::class.java))
        vm.start()
        val running = manager.getNotification(StopwatchNotifications.ID)
        assertNotNull(running)
        assertTrue(running.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(running.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertEquals(2, running.actions.size)
        assertEquals("pause", shadowOf(running.actions[0].actionIntent).savedIntent.action)
        assertEquals("lap", shadowOf(running.actions[1].actionIntent).savedIntent.action)
        CloudUtilityController(context).applyNotificationStopwatchAction("lap")
        assertEquals(1, (CloudUtilityController.stopwatchSnapshot(context)["laps"] as List<*>).size)
        CloudUtilityController(context).applyNotificationStopwatchAction("pause")
        val paused = manager.getNotification(StopwatchNotifications.ID)
        assertFalse(paused.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertEquals(1, paused.actions.size)
        assertEquals("resume", shadowOf(paused.actions[0].actionIntent).savedIntent.action)
        CloudUtilityController(context).applyNotificationStopwatchAction("resume")
        assertEquals("RUNNING", CloudUtilityController.stopwatchSnapshot(context)["state"])
        vm.reset()
        assertNull(manager.getNotification(StopwatchNotifications.ID))
        prefs.edit().clear().commit()
    }
}
