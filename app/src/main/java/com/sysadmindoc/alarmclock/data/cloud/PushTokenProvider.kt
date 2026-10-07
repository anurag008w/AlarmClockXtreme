package com.sysadmindoc.alarmclock.data.cloud

/**
 * Source of the device push token used by the cloud server to wake this app
 * when alarms or settings change. The Play flavor returns a Firebase Cloud
 * Messaging token; the F-Droid flavor has no proprietary push and returns "".
 * An empty token simply means "poll only", exactly like earlier versions.
 */
interface PushTokenProvider {
    suspend fun currentToken(): String
}
