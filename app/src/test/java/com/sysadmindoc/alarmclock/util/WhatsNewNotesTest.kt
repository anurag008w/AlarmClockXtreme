package com.sysadmindoc.alarmclock.util

import com.sysadmindoc.alarmclock.BuildConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsNewNotesTest {
    @Test
    fun currentVersionHasReleaseNotes() {
        assertTrue(
            "Add a WhatsNewNotes entry for ${BuildConfig.VERSION_NAME}",
            WhatsNewNotes.has(BuildConfig.VERSION_NAME)
        )
    }

    @Test
    fun linksPointToOwnRepository() {
        assertFalse(WhatsNewNotes.REPO_URL.contains("SysAdminDoc", ignoreCase = true))
        assertTrue(WhatsNewNotes.releaseNotesUrl("1.17.0").startsWith("https://github.com/anurag008w/AlarmClockXtreme/releases/tag/v"))
    }
}
