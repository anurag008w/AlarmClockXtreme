package com.sysadmindoc.alarmclock.data.cloud

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.sysadmindoc.alarmclock.BuildConfig

/**
 * Initialises Firebase from build-time public identifiers instead of the
 * google-services Gradle plugin, so the project keeps its pinned plugin set.
 * These values identify the Firebase project; they are not secrets.
 */
object PlayFirebase {
    @Synchronized
    fun ensureInitialized(context: Context): Boolean {
        val app = context.applicationContext
        if (FirebaseApp.getApps(app).isNotEmpty()) return true
        if (BuildConfig.FCM_APP_ID.isBlank() || BuildConfig.FCM_API_KEY.isBlank()) return false
        return runCatching {
            FirebaseApp.initializeApp(
                app,
                FirebaseOptions.Builder()
                    .setApplicationId(BuildConfig.FCM_APP_ID)
                    .setApiKey(BuildConfig.FCM_API_KEY)
                    .setProjectId(BuildConfig.FCM_PROJECT_ID)
                    .setGcmSenderId(BuildConfig.FCM_SENDER_ID)
                    .build()
            )
        }.isSuccess
    }
}
