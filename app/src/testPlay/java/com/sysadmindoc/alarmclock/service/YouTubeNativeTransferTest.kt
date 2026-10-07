package com.sysadmindoc.alarmclock.service

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class YouTubeNativeTransferTest {
    @Test fun transferUsesEngineAndNeverSeparateCdnGet() {
        val file=File("src/play/java/com/sysadmindoc/alarmclock/service/PlayYouTubeAudioDownloader.kt")
        val source=(if(file.exists()) file else File("app/"+file.path)).readText()
        val preview=source.substringAfter("override suspend fun getPreviewStreamUrl").substringBefore("override suspend fun searchAlarmSounds")
        assertFalse(preview.contains("--get-url"))
        assertTrue(preview.contains("native-preview"))
        assertTrue(preview.contains("60M"))
        assertTrue(preview.contains("worstaudio[ext=m4a]"))
        assertTrue(preview.contains("completedYouTubeAudio"))
        val download=source.substringAfter("override suspend fun downloadAsAlarm").substringBefore("companion object")
        assertFalse(download.contains("--get-url"))
        assertFalse(download.contains("httpClient.newCall"))
        assertTrue(download.contains("--http-chunk-size"))
        assertTrue(download.contains("--max-filesize"))
        assertTrue(download.contains("--no-playlist"))
        assertTrue(download.contains("directory.deleteRecursively()"))
        assertTrue(download.contains("native-download"))
    }
}
