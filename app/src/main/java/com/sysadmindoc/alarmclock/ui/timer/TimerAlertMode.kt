package com.sysadmindoc.alarmclock.ui.timer

import android.content.Context

/** User choice for how a finished timer alerts: ringtone + vibration (default) or vibration only. */
object TimerAlertMode {
    private const val PREFS = "timer_alert_mode"
    private const val KEY_VIBRATE_ONLY = "vibrate_only"

    fun isVibrateOnly(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_VIBRATE_ONLY, false)

    fun setVibrateOnly(context: Context, value: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_VIBRATE_ONLY, value).apply()
    }
}
