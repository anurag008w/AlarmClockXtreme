package com.sysadmindoc.alarmclock.util

import com.sysadmindoc.alarmclock.data.update.VersionCompare
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionCompareTest {
    @Test fun newerPatch() = assertTrue(VersionCompare.isNewer("1.16.2", "1.16.1"))
    @Test fun sameVersionIsNotNewer() = assertFalse(VersionCompare.isNewer("v1.16.1", "1.16.1"))
    @Test fun olderIsNotNewer() = assertFalse(VersionCompare.isNewer("1.15.52", "1.16.1"))
    @Test fun numericNotLexical() = assertTrue(VersionCompare.isNewer("1.16.10", "1.16.9"))
    @Test fun minorBeatsPatch() = assertTrue(VersionCompare.isNewer("1.17.0", "1.16.99"))
}
