package com.sysadmindoc.alarmclock.service

import java.nio.file.Files
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class YouTubeTransferResultTest {
    private fun checkFailure(exit: Int = 0, out: String = "", create: (java.io.File)->Unit = {}): String {
        val dir=Files.createTempDirectory("audio-result").toFile()
        try {
            create(dir)
            try { completedYouTubeAudio(dir,exit,out,"",1024); fail("Expected failure") }
            catch(e: IOException) { return e.message.orEmpty() }
            return ""
        } finally { dir.deleteRecursively() }
    }
    @Test fun mp4AudioContainerIsAccepted() {
        val dir=Files.createTempDirectory("audio-result").toFile()
        try { val f=java.io.File(dir,"audio.mp4").apply { writeBytes(byteArrayOf(1,2)) }
            assertEquals(f,completedYouTubeAudio(dir,0,"","",1024))
        } finally { dir.deleteRecursively() }
    }
    @Test fun zeroExitSizeSkipIsExplicit() { assertTrue(checkFailure(out="File is larger than max-filesize (2000 bytes > 1024 bytes). Aborting.").contains("exceeded size limit")) }
    @Test fun killedEngineIsNotSuccess() { assertTrue(checkFailure(exit=-9).contains("code=-9")) }
    @Test fun partialIsNeverPlayable() { assertTrue(checkFailure(create={java.io.File(it,"audio.webm.part").writeText("partial")}).contains("partial=1")) }
    @Test fun ambiguityDoesNotPickRandomFile() { assertTrue(checkFailure(create={java.io.File(it,"audio.m4a").writeText("a");java.io.File(it,"audio.webm").writeText("b")}).contains("complete=2")) }
    @Test fun emptyAudioFails() { assertTrue(checkFailure(create={java.io.File(it,"audio.webm").createNewFile()}).contains("complete=0")) }
    @Test fun engineOutputIsNotDisclosed() { val m=checkFailure(out="https://private.invalid/?token=secret 403");assertFalse(m.contains("private"));assertFalse(m.contains("secret"));assertTrue(m.contains("403")) }
}
