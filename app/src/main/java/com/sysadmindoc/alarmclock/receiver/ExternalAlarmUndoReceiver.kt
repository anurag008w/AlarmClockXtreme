package com.sysadmindoc.alarmclock.receiver

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sysadmindoc.alarmclock.AlarmClockApp
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Undo button on the "alarm changed by a paired app" notice. Not exported. */
class ExternalAlarmUndoReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_UNDO) return
        val alarmId = intent.getLongExtra(EXTRA_ALARM_ID, -1L)
        if (alarmId <= 0L) return
        val prevHour = intent.getIntExtra(EXTRA_HOUR, -1)
        val prevMinute = intent.getIntExtra(EXTRA_MINUTE, -1)
        val prevEnabled = intent.getBooleanExtra(EXTRA_ENABLED, false)
        val created = intent.getBooleanExtra(EXTRA_CREATED, false)
        val app = context.applicationContext
        val pending = goAsync()
        scope.launch {
            try {
                withTimeout(8_000L) {
                    val ep = EntryPointAccessors.fromApplication(app, AlarmClockApp.AppEntryPoint::class.java)
                    val repo = ep.alarmRepository()
                    val scheduler = ep.alarmScheduler()
                    val calculator = ep.nextAlarmCalculator()
                    val alarm = repo.getById(alarmId)
                    if (alarm != null) {
                        if (created) {
                            scheduler.cancel(alarmId)
                            repo.deleteById(alarmId)
                            scheduler.syncBedtimeDndRule()
                        } else {
                            val restored = alarm.copy(
                                hour = if (prevHour in 0..23) prevHour else alarm.hour,
                                minute = if (prevMinute in 0..59) prevMinute else alarm.minute,
                                isEnabled = prevEnabled
                            )
                            repo.update(restored)
                            if (prevEnabled) {
                                val trigger = calculator.calculate(restored)
                                repo.setEnabled(alarmId, enabled = true, nextTrigger = trigger)
                                scheduler.schedule(restored.copy(nextTriggerTime = trigger))
                            } else {
                                repo.setEnabled(alarmId, enabled = false, nextTrigger = 0)
                                scheduler.cancel(alarmId)
                                scheduler.syncBedtimeDndRule()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("ExternalAlarmUndo", "Undo failed for alarm $alarmId", e)
            } finally {
                runCatching {
                    app.getSystemService(NotificationManager::class.java)
                        ?.cancel(6_700 + (alarmId % 1000).toInt())
                }
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_UNDO = "com.sysadmindoc.alarmclock.action.EXTERNAL_ALARM_UNDO"
        const val EXTRA_ALARM_ID = "alarm_id"
        const val EXTRA_HOUR = "prev_hour"
        const val EXTRA_MINUTE = "prev_minute"
        const val EXTRA_ENABLED = "prev_enabled"
        const val EXTRA_CREATED = "created"
    }
}
