package com.sysadmindoc.alarmclock.data.update

import com.sysadmindoc.alarmclock.util.WhatsNewNotes
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** A published GitHub release that carries an installable APK. */
data class ReleaseInfo(
    val versionName: String,
    val notes: List<String>,
    val apkUrl: String,
    val apkName: String,
    val apkSize: Long,
    val pageUrl: String
)

object VersionCompare {
    /** True only when [remote] is strictly newer than [local] (numeric, dot-separated). */
    fun isNewer(remote: String, local: String): Boolean {
        val r = parts(remote)
        val l = parts(local)
        val n = maxOf(r.size, l.size)
        for (i in 0 until n) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun parts(v: String): List<Int> =
        v.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
            .split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
}

object UpdateChecker {
    private const val LATEST_URL =
        "https://api.github.com/repos/anurag008w/AlarmClockXtreme/releases/latest"

    /** Returns the latest published release, or null when it has no APK. Throws on network/parse failure. */
    suspend fun fetchLatest(): ReleaseInfo? = withContext(Dispatchers.IO) {
        val conn = (URL(LATEST_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "AlarmClockXtreme-updater")
        }
        try {
            if (conn.responseCode != 200) throw java.io.IOException("GitHub returned ${conn.responseCode}")
            parse(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    internal fun parse(json: String): ReleaseInfo? {
        val o = JSONObject(json)
        if (o.optBoolean("draft") || o.optBoolean("prerelease")) return null
        val version = o.optString("tag_name").removePrefix("v").removePrefix("V")
        if (version.isBlank()) return null
        val assets = o.optJSONArray("assets") ?: return null
        var best: JSONObject? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name")
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            if (best == null || name.contains("play", ignoreCase = true)) best = a
        }
        val apk = best ?: return null
        val notes = if (WhatsNewNotes.has(version)) {
            WhatsNewNotes.forVersion(version)
        } else {
            bodyToBullets(o.optString("body"))
        }
        return ReleaseInfo(
            versionName = version,
            notes = notes,
            apkUrl = apk.optString("browser_download_url"),
            apkName = apk.optString("name"),
            apkSize = apk.optLong("size", -1L),
            pageUrl = o.optString("html_url").ifBlank { WhatsNewNotes.releaseNotesUrl(version) }
        )
    }

    internal fun bodyToBullets(body: String): List<String> {
        val lines = body.lines()
            .map { it.trim().removePrefix("-").removePrefix("*").trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("Release:") }
            .take(6)
        return lines.ifEmpty { listOf("See the full release notes on GitHub.") }
    }
}
