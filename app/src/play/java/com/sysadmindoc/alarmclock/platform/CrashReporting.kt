package com.sysadmindoc.alarmclock.platform

import android.content.Context
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.sysadmindoc.alarmclock.BuildConfig
import com.sysadmindoc.alarmclock.data.cloud.PlayFirebase

/**
 * Play flavor: sends uncaught crashes to Firebase Crashlytics. Safe to call more than
 * once. Reporting can never break startup: any failure just leaves it disabled.
 */
object CrashReporting {
    fun install(context: Context) {
        runCatching {
            if (!PlayFirebase.ensureInitialized(context)) return
            val crashlytics = FirebaseCrashlytics.getInstance()
            crashlytics.setCrashlyticsCollectionEnabled(true)
            crashlytics.setCustomKey("app_version", BuildConfig.VERSION_NAME)
        }
    }
}
