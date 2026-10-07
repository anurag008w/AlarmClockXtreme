package com.sysadmindoc.alarmclock.data.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PushMessagePolicyTest {
    @Test fun onlyDataOnlySyncMessagesStartASync() {
        assertTrue(PushMessagePolicy.shouldSync(mapOf("type" to "sync", "reason" to "change")))
        assertFalse(PushMessagePolicy.shouldSync(emptyMap()))
        assertFalse(PushMessagePolicy.shouldSync(mapOf("type" to "alarm")))
        assertFalse(PushMessagePolicy.shouldSync(mapOf("reason" to "sync")))
    }

    @Test fun messageContentCanNeverChangeAlarms() {
        // The policy has no alarm payload input at all: it only reads "type".
        assertFalse(PushMessagePolicy.shouldSync(mapOf("hour" to "3", "delete" to "all")))
    }
}
