package com.sysadmindoc.alarmclock.data.cloud

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Receives the cloud server's data-only wake-up. The message never carries
 * alarm content: it only starts a normal cloud sync, so a lost, repeated or
 * forged push cannot change an alarm by itself.
 */
class PlayFcmService : FirebaseMessagingService() {
    override fun onCreate() {
        super.onCreate()
        PlayFirebase.ensureInitialized(applicationContext)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (!PushMessagePolicy.shouldSync(message.data)) return
        CloudSyncWorker.enqueueFromPush(applicationContext)
    }

    override fun onNewToken(token: String) {
        // The sync registers the rotated token with the server.
        CloudSyncWorker.enqueueFromPush(applicationContext)
    }
}
