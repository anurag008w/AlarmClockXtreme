package com.sysadmindoc.alarmclock.data.cloud

object PushMessagePolicy {
    /** Only the server's data-only "sync" wake-up starts a sync. */
    fun shouldSync(data: Map<String, String>): Boolean = data["type"] == "sync"
}
