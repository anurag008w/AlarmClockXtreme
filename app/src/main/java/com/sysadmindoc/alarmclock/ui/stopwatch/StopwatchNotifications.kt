package com.sysadmindoc.alarmclock.ui.stopwatch

import kotlinx.coroutines.launch
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.sysadmindoc.alarmclock.MainActivity
import com.sysadmindoc.alarmclock.R
import com.sysadmindoc.alarmclock.data.cloud.CloudUtilityController

/** System chronometer advances without a service, polling loop or wake lock. */
object StopwatchNotifications {
    internal const val ID = 6510
    internal const val CHANNEL = "running_stopwatch"

    fun refresh(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL,
                context.getString(R.string.stopwatch_title), NotificationManager.IMPORTANCE_LOW))
        }
        val snapshot = CloudUtilityController.stopwatchSnapshot(context)
        val state = snapshot["state"] as String
        if (state == "IDLE") { manager.cancel(ID); return }
        val running = state == "RUNNING"
        val elapsed = snapshot["elapsedMillis"] as Long
        val open = PendingIntent.getActivity(context, ID, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val action = PendingIntent.getBroadcast(context, ID,
            Intent(context, StopwatchNotificationReceiver::class.java).setAction(if(running) "pause" else "resume"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val lap = PendingIntent.getBroadcast(context, ID + 1,
            Intent(context, StopwatchNotificationReceiver::class.java).setAction("lap"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(context.getString(R.string.stopwatch_title))
            .setContentText(if(running) context.getString(R.string.stopwatch_running) else
                context.getString(R.string.settings_paused) + " · " + formatElapsed(elapsed))
            .setWhen(System.currentTimeMillis() - elapsed).setShowWhen(running)
            .setUsesChronometer(running).setOngoing(running).setOnlyAlertOnce(true).setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS).setContentIntent(open)
            .addAction(0, context.getString(if(running) R.string.alarm_list_pause else R.string.stopwatch_resume), action)
        if(running) builder.addAction(0, context.getString(R.string.stopwatch_lap), lap)
        val notification = builder.build()
        try { NotificationManagerCompat.from(context).notify(ID, notification) }
        catch (_: SecurityException) { /* Stopwatch still works if notification access is denied. */ }
    }

    internal fun formatElapsed(ms: Long): String = java.lang.String.format(java.util.Locale.ROOT,
        "%02d:%02d:%02d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60)
}

class StopwatchNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if(action !in listOf("pause", "resume", "lap")) return
        val pending = goAsync()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                val state = CloudUtilityController.stopwatchSnapshot(context)["state"]
                if((action in listOf("pause", "lap") && state == "RUNNING") || (action == "resume" && state == "PAUSED")) {
                    // Use the same locked, persisted transition as remote controls.
                    runCatching { CloudUtilityController(context).applyNotificationStopwatchAction(action) }
                }
            } finally { pending.finish() }
        }
    }
}
