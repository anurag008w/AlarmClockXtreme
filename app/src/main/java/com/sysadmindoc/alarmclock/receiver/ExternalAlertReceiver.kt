package com.sysadmindoc.alarmclock.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sysadmindoc.alarmclock.platform.ExternalAlertStore
import com.sysadmindoc.alarmclock.service.ExternalAlertAck
import com.sysadmindoc.alarmclock.service.ExternalAlertService

/**
 * Entry point for a paired app that wants to vibrate or ring this phone.
 * Exported so other apps can reach it, therefore it ignores everything that
 * does not carry the pairing code, and it is off unless the user switched
 * "External alerts" on in Settings.
 */
class ExternalAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_EXTERNAL_ALERT) return
        val alertId = intent.getStringExtra("alert_id").orEmpty().trim().take(64)
        if (!ExternalAlertStore.isEnabled(context)) return
        if (!ExternalAlertStore.tokenMatches(context, intent.getStringExtra("token"))) {
            // Wrong or missing code: stay silent unless an id was sent, then say rejected.
            ExternalAlertAck.send(context, alertId, ExternalAlertAck.REJECTED)
            return
        }
        if (alertId.isEmpty()) return
        val level = if (intent.getStringExtra("level") == "ring") "ring" else "vibrate"
        val message = intent.getStringExtra("message").orEmpty().trim().take(200)
        val duration = intent.getIntExtra("duration_s", DEFAULT_DURATION_S).coerceIn(5, 120)
        val reason = intent.getStringExtra("reason").orEmpty().take(120)
        val source = intent.getStringExtra("source").orEmpty().take(40)
        if (ExternalAlertService.isActive || !ExternalAlertStore.tryAcceptNow(context)) {
            ExternalAlertAck.send(context, alertId, ExternalAlertAck.REJECTED)
            return
        }
        ExternalAlertStore.recordLast(
            context,
            listOf(source, reason, message).filter { it.isNotBlank() }.joinToString(" - ")
                .ifBlank { alertId }
        )
        ExternalAlertService.start(context, alertId, level, message, duration)
    }

    companion object {
        const val ACTION_EXTERNAL_ALERT = "com.alarmclockxtreme.action.EXTERNAL_ALERT"
        const val DEFAULT_DURATION_S = 60
    }
}
