package com.sysadmindoc.alarmclock.data.cloud

import javax.inject.Inject
import javax.inject.Singleton

/** F-Droid flavor: no proprietary push, the app keeps syncing by polling. */
@Singleton
class FdroidPushTokenProvider @Inject constructor() : PushTokenProvider {
    override suspend fun currentToken(): String = ""
}
