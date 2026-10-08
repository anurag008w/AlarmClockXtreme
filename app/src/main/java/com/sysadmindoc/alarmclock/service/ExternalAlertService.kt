package com.sysadmindoc.alarmclock.service

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.sysadmindoc.alarmclock.R
import com.sysadmindoc.alarmclock.receiver.ExternalAlertFireReceiver
import com.sysadmindoc.alarmclock.ui.externalalert.ExternalAlertActivity

/**
 * Plays one short alert (vibrate only, or vibrate plus ringtone) for a paired
 * app. Only one alert runs at a time. It stops on the Stop button or when the
 * requested duration ends, and tells the paired app how it ended.
 */
class ExternalAlertService : Service() {

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var alertId: String = ""
    private val handler = Handler(Looper.getMainLooper())
    private val expire = Runnable { finish(ExternalAlertAck.EXPIRED) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val id = intent.getStringExtra(EXTRA_ALERT_ID).orEmpty()
                val level = intent.getStringExtra(EXTRA_LEVEL).orEmpty()
                val message = intent.getStringExtra(EXTRA_MESSAGE).orEmpty()
                val duration = intent.getIntExtra(EXTRA_DURATION_S, 60).coerceIn(5, 120)
                if (isActive) {
                    startForegroundAlert(message)
                    ExternalAlertAck.send(this, id, ExternalAlertAck.REJECTED)
                    return START_NOT_STICKY
                }
                alertId = id
                isActive = true
                if (!startForegroundAlert(message)) {
                    ExternalAlertAck.send(this, id, ExternalAlertAck.ERROR)
                    stopAndReset()
                    return START_NOT_STICKY
                }
                vibrate()
                if (level == "ring") playSound()
                handler.removeCallbacks(expire)
                handler.postDelayed(expire, duration * 1000L)
                ExternalAlertAck.send(this, id, ExternalAlertAck.STARTED)
            }
            ACTION_STOP -> finish(ExternalAlertAck.DISMISSED)
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun startForegroundAlert(message: String): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.ext_alert_channel_name),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    setSound(null, null)
                    enableVibration(false)
                }
            )
        }
        val text = message.ifBlank { getString(R.string.ext_alert_default_message) }
        val open = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            ExternalAlertActivity.intent(this, text),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            NOTIFICATION_ID + 1,
            Intent(this, ExternalAlertService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(getString(R.string.ext_alert_title))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(open, true)
            .setContentIntent(open)
            .addAction(R.drawable.ic_alarm, getString(R.string.ext_alert_stop), stop)
            .build()
        return runCatching { startForeground(NOTIFICATION_ID, notification) }
            .onFailure { Log.w(TAG, "startForeground failed", it) }
            .isSuccess
    }

    private fun vibrate() {
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
        vibrator = v
        runCatching {
            @Suppress("DEPRECATION")
            v?.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 600, 400, 600, 400), 0),
                AlarmAudioRouting.alarmSonificationAttributes()
            )
        }.onFailure { Log.w(TAG, "vibrate failed", it) }
    }

    private fun playSound() {
        var player: MediaPlayer? = null
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: return
            player = MediaPlayer()
            player?.apply {
                setAudioAttributes(AlarmAudioRouting.alarmSonificationAttributes())
                setDataSource(this@ExternalAlertService, uri)
                isLooping = true
                prepare()
                start()
            }
            mediaPlayer = player
        }.onFailure {
            runCatching { player?.release() }
            mediaPlayer = null
            Log.w(TAG, "ringtone failed", it)
        }
    }

    private fun finish(status: String) {
        if (isActive) ExternalAlertAck.send(this, alertId, status)
        stopAndReset()
    }

    private fun stopAndReset() {
        handler.removeCallbacks(expire)
        runCatching { mediaPlayer?.let { if (it.isPlaying) it.stop(); it.release() } }
        mediaPlayer = null
        runCatching { vibrator?.cancel() }
        vibrator = null
        isActive = false
        ExternalAlertActivity.close()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(expire)
        runCatching { mediaPlayer?.release() }
        runCatching { vibrator?.cancel() }
        isActive = false
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ExternalAlertService"
        private const val CHANNEL_ID = "external_alert_channel"
        private const val NOTIFICATION_ID = 6_601
        const val ACTION_START = "com.sysadmindoc.alarmclock.action.EXTERNAL_ALERT_START"
        const val ACTION_STOP = "com.sysadmindoc.alarmclock.action.EXTERNAL_ALERT_STOP"
        const val ACTION_FIRE_INTERNAL = "com.sysadmindoc.alarmclock.action.EXTERNAL_ALERT_FIRE"
        const val EXTRA_ALERT_ID = "alert_id"
        const val EXTRA_LEVEL = "level"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_DURATION_S = "duration_s"

        @Volatile
        var isActive: Boolean = false
            private set

        /** Start the alert. If Android blocks a direct start, retry through a one-shot alarm-clock hop. */
        fun start(context: Context, alertId: String, level: String, message: String, durationS: Int) {
            val started = runCatching { startForeground(context, alertId, level, message, durationS, report = false) }
                .getOrDefault(false)
            if (started) return
            val sent = runCatching {
                val am = context.getSystemService(AlarmManager::class.java)
                val fire = Intent(context, ExternalAlertFireReceiver::class.java)
                    .setAction(ACTION_FIRE_INTERNAL)
                    .putExtra(EXTRA_ALERT_ID, alertId)
                    .putExtra(EXTRA_LEVEL, level)
                    .putExtra(EXTRA_MESSAGE, message)
                    .putExtra(EXTRA_DURATION_S, durationS)
                val pi = PendingIntent.getBroadcast(
                    context, 6_602, fire,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val show = PendingIntent.getActivity(
                    context, 6_603, ExternalAlertActivity.intent(context, message),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                am.setAlarmClock(AlarmManager.AlarmClockInfo(System.currentTimeMillis() + 400, show), pi)
            }.isSuccess
            if (!sent) ExternalAlertAck.send(context, alertId, ExternalAlertAck.ERROR)
        }

        /** Direct foreground start. Returns true when Android accepted the start. */
        fun startForeground(
            context: Context,
            alertId: String,
            level: String,
            message: String,
            durationS: Int,
            report: Boolean = true
        ): Boolean {
            val intent = Intent(context, ExternalAlertService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_ALERT_ID, alertId)
                .putExtra(EXTRA_LEVEL, level)
                .putExtra(EXTRA_MESSAGE, message)
                .putExtra(EXTRA_DURATION_S, durationS)
            val ok = runCatching { context.startForegroundService(intent) }
                .onFailure { Log.w(TAG, "Could not start external alert service", it) }
                .isSuccess
            if (!ok && report) ExternalAlertAck.send(context, alertId, ExternalAlertAck.ERROR)
            return ok
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, ExternalAlertService::class.java).setAction(ACTION_STOP)
                )
            }
        }
    }
}
