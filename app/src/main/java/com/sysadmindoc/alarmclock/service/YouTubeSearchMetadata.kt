package com.sysadmindoc.alarmclock.service

import org.json.JSONObject

/** Parses flat yt-dlp search metadata without resolving or downloading audio. */
internal object YouTubeSearchMetadata {
    fun parse(json: String, maxDurationSeconds: Int): List<YouTubeSearchHit> {
        require(maxDurationSeconds > 0)
        val entries = JSONObject(json).optJSONArray("entries") ?: return emptyList()
        val seen = mutableSetOf<String>()
        return buildList {
            for (i in 0 until entries.length()) {
                val entry = entries.optJSONObject(i) ?: continue
                val id = entry.optString("id")
                val title = entry.optString("title").trim()
                val duration = entry.optDouble("duration", Double.NaN)
                if (!id.matches(Regex("[A-Za-z0-9_-]{11}")) || !seen.add(id)) continue
                if (title.isBlank() || '#' in title || !duration.isFinite() ||
                    duration < 1 || duration > maxDurationSeconds) continue
                add(YouTubeSearchHit(
                    videoUrl = "https://www.youtube.com/watch?v=$id",
                    title = title,
                    uploader = entry.optString("uploader").ifBlank { entry.optString("channel") },
                    durationSeconds = duration.toLong(),
                ))
                if (size == 15) break
            }
        }
    }
}

/** Engine versions can be displayed by the library with a redundant yt-dlp prefix. */
internal fun normalizedYouTubeEngineVersion(value: String?): String? =
    value?.trim()?.removePrefix("yt-dlp ")?.removePrefix("v")
        ?.takeIf { it.matches(Regex("\\d{4}\\.\\d{2}\\.\\d{2}")) }

data class YouTubeEngineRelease(val currentVersion: String?, val latestVersion: String) {
    val updateAvailable: Boolean get() {
        val current = normalizedYouTubeEngineVersion(currentVersion) ?: return false
        val latest = normalizedYouTubeEngineVersion(latestVersion) ?: return false
        return latest > current
    }
}
