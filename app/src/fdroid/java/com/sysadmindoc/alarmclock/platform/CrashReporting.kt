package com.sysadmindoc.alarmclock.platform

import android.content.Context

/** F-Droid flavor: no crash reporting service, no Google dependencies. */
object CrashReporting {
    @Suppress("UNUSED_PARAMETER")
    fun install(context: Context) = Unit
}
