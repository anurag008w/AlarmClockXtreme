package com.sysadmindoc.alarmclock.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sysadmindoc.alarmclock.service.ExternalAlertService

/** Internal hop used when Android blocks starting the alert service straight from the exported receiver. */
class ExternalAlertFireReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ExternalAlertService.ACTION_FIRE_INTERNAL) return
        ExternalAlertService.startForeground(
            context,
            intent.getStringExtra(ExternalAlertService.EXTRA_ALERT_ID).orEmpty(),
            intent.getStringExtra(ExternalAlertService.EXTRA_LEVEL).orEmpty(),
            intent.getStringExtra(ExternalAlertService.EXTRA_MESSAGE).orEmpty(),
            intent.getIntExtra(ExternalAlertService.EXTRA_DURATION_S, 60)
        )
    }
}
