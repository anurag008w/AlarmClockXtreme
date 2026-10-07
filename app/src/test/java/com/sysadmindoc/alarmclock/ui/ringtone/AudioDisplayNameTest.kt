package com.sysadmindoc.alarmclock.ui.ringtone
import org.junit.Assert.assertEquals
import org.junit.Test
class AudioDisplayNameTest {
    @Test fun userNameWins() { assertEquals("h",audioDisplayName("h","Video title","fallback")) }
    @Test fun legacyBlankUsesFilename() { assertEquals("rooster-alarm",audioDisplayName("  ","rooster-alarm.m4a","fallback")) }
    @Test fun videoTitleForNoName() { assertEquals("Rooster Crow",audioDisplayName("","Rooster Crow","fallback")) }
    @Test fun unknownHasVisibleFallback() { assertEquals("Alarm sound",audioDisplayName(null,null,"Alarm sound")) }
}
