package com.sysadmindoc.alarmclock.data.cloud

import android.content.Context
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Play flavor: FCM registration token, or "" when Firebase is unavailable. */
@Singleton
class PlayPushTokenProvider @Inject constructor(
    @ApplicationContext private val context: Context
) : PushTokenProvider {
    override suspend fun currentToken(): String {
        if (!PlayFirebase.ensureInitialized(context)) return ""
        return suspendCancellableCoroutine { cont ->
            runCatching {
                FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                    val token = if (task.isSuccessful) task.result.orEmpty() else ""
                    if (cont.isActive) cont.resume(token)
                }
            }.onFailure { if (cont.isActive) cont.resume("") }
        }
    }
}
