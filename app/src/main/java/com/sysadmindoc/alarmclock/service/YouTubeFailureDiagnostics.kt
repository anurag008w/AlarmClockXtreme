package com.sysadmindoc.alarmclock.service

import android.content.Context
import java.util.Collections
import java.util.IdentityHashMap

/** Bounded, identity-safe traversal includes the failed primary search engine. */
internal fun youTubeFailureChain(error: Throwable): List<Throwable> {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    val queue = java.util.ArrayDeque<Throwable>().apply { add(error) }
    val result = mutableListOf<Throwable>()
    while(queue.isNotEmpty() && result.size < 16) {
        val next = queue.removeFirst()
        if(!seen.add(next)) continue
        result.add(next)
        next.cause?.let { queue.add(it) }
        next.suppressed.take(4).forEach { queue.add(it) }
    }
    return result
}

/** Never stores query, URL, headers, message text or stack traces. */
internal object YouTubeFailureDiagnostics {
    fun category(error: Throwable): String {
        val chain = youTubeFailureChain(error)
        val text = chain.joinToString(" ") { it.javaClass.simpleName + " " + it.message.orEmpty() }.lowercase()
        return when {
            chain.any { it is LinkageError } -> "compatibility"
            chain.any { it is java.net.UnknownHostException } || "name resolution" in text -> "dns"
            chain.any { it is javax.net.ssl.SSLException } || "certificate_verify_failed" in text -> "tls"
            chain.any { it is java.net.SocketTimeoutException } || "timed out" in text -> "timeout"
            "403" in text || "429" in text || "captcha" in text || "not a bot" in text -> "blocked"
            "cannot link executable" in text || "permission denied" in text || "no such file" in text -> "runtime"
            "parsing" in text || "extractor" in text || "signature" in text || "player response" in text -> "extractor"
            "mediastore" in text || "output stream" in text -> "storage"
            chain.any { it is java.io.IOException } -> "io"
            else -> "unknown"
        }
    }
    fun record(context: Context, stage: String, error: Throwable) {
        val safeStage = stage.takeIf { it in setOf("search", "newpipe-search", "ytdlp-search", "stream-resolve", "native-download", "native-preview", "preview-playback", "audio-copy", "audio-save") } ?: "unknown"
        val chain = youTubeFailureChain(error)
        val allText = chain.joinToString(" ") { it.message.orEmpty() }.lowercase()
        val signals = listOf("certificate_verify_failed", "name resolution", "timed out", "permission denied", "cannot link executable", "no such file", "not a bot", "captcha", "403", "429", "signature", "player response", "unsupported locale", "javascript runtime", "requested format is not available", "mediastore", "relative_path", "connection reset", "unexpected eof", "unexpected end", "broken pipe", "handshake", "certificate", "trust anchor", "protocol error", "ssl_read", "ssl routines", "http error", "no complete audio", "exceeded size limit", "nonzero exit")
            .filter { it in allText }.joinToString(",")
        val classes = chain.map { it.javaClass.simpleName.replace(Regex("[^A-Za-z0-9_$]"), "").take(80) }.joinToString(",")
        val counts = Regex("(?:complete|partial|other|code)=-?\\d+").findAll(allText).take(4).joinToString(",") { it.value }
        val row = "${System.currentTimeMillis()} stage=$safeStage category=${category(error)} classes=$classes signals=$signals counts=$counts"
        synchronized(this) {
            val prefs = context.getSharedPreferences("youtube_diagnostics", Context.MODE_PRIVATE)
            val rows = prefs.getString("failures", "").orEmpty().lines().filter { it.isNotBlank() }.takeLast(9) + row
            prefs.edit().putString("failures", rows.joinToString("\n")).apply()
        }
    }
    fun report(context: Context): String = context.getSharedPreferences("youtube_diagnostics", Context.MODE_PRIVATE)
        .getString("failures", "none") ?: "none"
}
