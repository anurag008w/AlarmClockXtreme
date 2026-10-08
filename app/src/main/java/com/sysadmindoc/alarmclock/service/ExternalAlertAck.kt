package com.sysadmindoc.alarmclock.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** Status replies for external alerts, sent as an explicit broadcast to the paired app. */
object ExternalAlertAck {
    const val ACTION_STATUS = "com.divya.action.ALERT_STATUS"
    private const val TARGET_PACKAGE = "app.divya.agent"
    private const val TARGET_CLASS = "app.divya.agent.AlertStatusReceiver"

    const val STARTED = "started"
    const val DISMISSED = "dismissed"
    const val EXPIRED = "expired"
    const val REJECTED = "rejected"
    const val ERROR = "error"

    fun send(context: Context, alertId: String, status: String) {
        if (alertId.isBlank()) return
        runCatching {
            context.sendBroadcast(
                Intent(ACTION_STATUS)
                    .setComponent(ComponentName(TARGET_PACKAGE, TARGET_CLASS))
                    .putExtra("alert_id", alertId)
                    .putExtra("status", status)
            )
        }
    }
}
