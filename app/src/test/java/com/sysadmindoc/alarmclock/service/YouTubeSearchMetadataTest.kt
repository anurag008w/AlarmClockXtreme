package com.sysadmindoc.alarmclock.service

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class YouTubeSearchMetadataTest {
    @Test fun flatSearchKeepsOnlyBoundedValidUniqueVideos() {
        val json = """{"entries":[
            {"id":"abcdefghijk","title":"Alarm","duration":38,"channel":"Channel"},
            {"id":"abcdefghijk","title":"Duplicate","duration":38},
            {"id":"ABCDEFGHIJK","title":"Long","duration":3601},
            {"id":"12345678901","title":"Live"},
            {"id":"12345678902","title":"Short #tag","duration":20},
            {"id":"bad.id","title":"Invalid","duration":20},
            null
        ]}"""
        val hits = YouTubeSearchMetadata.parse(json, 600)
        assertEquals(1, hits.size)
        assertEquals("https://www.youtube.com/watch?v=abcdefghijk", hits.single().videoUrl)
        assertEquals("Channel", hits.single().uploader)
        assertEquals(38L, hits.single().durationSeconds)
    }

    @Test fun emptyResultsAreNotNetworkFailures() {
        assertTrue(YouTubeSearchMetadata.parse("{\"entries\":[]}", 600).isEmpty())
    }

    @Test fun engineOfferRequiresStrictlyNewerConfirmedVersion() {
        assertFalse(YouTubeEngineRelease("yt-dlp 2026.08.19", "2026.08.19").updateAvailable)
        assertTrue(YouTubeEngineRelease("2026.08.19", "2026.10.01").updateAvailable)
        assertFalse(YouTubeEngineRelease(null, "2026.10.01").updateAvailable)
        assertFalse(YouTubeEngineRelease("2026.10.01", "2026.08.19").updateAvailable)
        assertNull(normalizedYouTubeEngineVersion("not a version"))
    }
}
