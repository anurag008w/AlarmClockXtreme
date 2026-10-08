package com.sysadmindoc.alarmclock.platform

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle

/** Replies to the paired app for remote alarm requests, as an explicit broadcast. */
object ExternalAlarmAck {
    const val ACTION_STATUS = "com.divya.action.ALARM_STATUS"
    private const val TARGET_PACKAGE = "app.divya.agent"
    private const val TARGET_CLASS = "app.divya.agent.AlarmStatusReceiver"

    const val APPLIED = "applied"
    const val REJECTED = "rejected"
    const val ERROR = "error"

    fun send(context: Context, requestId: String, op: String, status: String, extras: Bundle = Bundle()) {
        if (requestId.isBlank()) return
        runCatching {
            context.sendBroadcast(
                Intent(ACTION_STATUS)
                    .setComponent(ComponentName(TARGET_PACKAGE, TARGET_CLASS))
                    .putExtras(extras)
                    .putExtra("request_id", requestId)
                    .putExtra("op", op)
                    .putExtra("status", status)
            )
        }
    }

    fun rejected(context: Context, requestId: String, op: String, reason: String) {
        send(context, requestId, op, REJECTED, Bundle().apply { putString("reason", reason) })
    }
}
