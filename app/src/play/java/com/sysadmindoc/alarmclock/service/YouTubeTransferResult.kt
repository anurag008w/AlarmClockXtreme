package com.sysadmindoc.alarmclock.service

import java.io.File
import java.io.IOException
import java.util.Locale

/** Only fixed markers and counts cross into failure diagnostics, never engine output. */
internal fun completedYouTubeAudio(directory: File, exitCode: Int, out: String, err: String, maxBytes: Long): File {
    val text = (out + "\n" + err).lowercase(Locale.ROOT)
    val files = directory.listFiles()?.filter { it.isFile }.orEmpty()
    val complete = files.filter {
        it.name.startsWith("audio.") && it.extension.lowercase(Locale.ROOT) in
            setOf("webm", "m4a", "mp4", "mp3", "ogg", "opus") && it.length() > 0
    }
    val partial = files.count { it.name.endsWith(".part") || it.name.endsWith(".ytdl") }
    val sizeSkipped = "larger than max-filesize" in text
    if (exitCode != 0) throw IOException("Native engine nonzero exit code=$exitCode")
    if (sizeSkipped) throw IOException("Native audio exceeded size limit")
    if (complete.size != 1) {
        val signals = listOf("403", "429", "certificate_verify_failed", "permission denied",
            "requested format is not available", "connection reset", "timed out")
            .filter { it in text }.joinToString(",")
        throw IOException("Native produced no complete audio file: complete=${complete.size} partial=$partial other=${files.size-complete.size-partial} signals=$signals")
    }
    val audio = complete.single()
    if (audio.length() > maxBytes) throw IOException("Native audio exceeded size limit")
    return audio
}
