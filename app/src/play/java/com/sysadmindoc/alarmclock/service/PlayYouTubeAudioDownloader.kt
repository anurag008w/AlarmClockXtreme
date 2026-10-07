package com.sysadmindoc.alarmclock.service

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real yt-dlp-backed downloader. Ported from
 * `~/repos/Aura/app/src/main/java/com/freevibe/data/repository/YouTubeRepository.kt`
 * (audio extraction) and `com/freevibe/service/SoundApplier.kt` (MediaStore write).
 *
 * Stripped down for the alarm-clock use case:
 *  - NewPipe search with a metadata-only yt-dlp fallback; downloads remain explicit.
 *  - No FFmpeg — the raw `bestaudio` stream is saved as-is. This keeps the APK
 *    smaller and avoids the FFmpeg LD_LIBRARY_PATH gymnastics from Aura.
 *  - Private temporary downloads are copied to MediaStore and cleaned up.
 *  - No stream-URL cache — alarm tones are downloaded once and reused from
 *    MediaStore, so a 6-hour token cache adds no value here.
 */
@Singleton
class PlayYouTubeAudioDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesManager: com.sysadmindoc.alarmclock.data.preferences.PreferencesManager,
) : YouTubeAudioDownloader {

    private val initialized = AtomicBoolean(false)
    private val engineUpdateInFlight = AtomicBoolean(false)

    /**
     * Session-only cache for resolved preview URLs. YouTube's signed audio
     * URLs are valid for ~6 hours; we cache for half that to leave headroom.
     * Bounded so a session that searches all day doesn't grow unbounded.
     */
    private data class CachedStream(val url: String, val cachedAtMs: Long)
    private val previewCache = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, CachedStream>(32, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, CachedStream>?,
            ): Boolean = size > 64
        }
    )

    // Independent client — bigger timeouts than the shared NetworkModule
    // OkHttpClient because alarm-tone downloads are larger than holiday-API
    // calls. 30 s connect / 5 min read covers a 30 MB clip on 4G.
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun markInitialized() {
        initialized.set(true)
    }

    override fun isAvailable(): Boolean = initialized.get()

    override fun engineVersionName(): String? =
        runCatching {
            if (!isAvailable()) return@runCatching null
            YoutubeDL.getInstance()
                .versionName(context)
                ?.trim()
                ?.ifBlank { null }
        }.getOrNull()

    private var releaseCheckCache: Pair<Long, YouTubeEngineRelease>? = null

    override suspend fun checkEngineRelease(): Result<YouTubeEngineRelease> = withContext(Dispatchers.IO) {
        runCatching {
            val current = normalizedYouTubeEngineVersion(engineVersionName())
            releaseCheckCache?.let { (time, release) ->
                if (release.currentVersion == current && System.currentTimeMillis() - time < 15 * 60_000) {
                    return@runCatching release
                }
            }
            val request = Request.Builder().url(GITHUB_RELEASES_LATEST)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "AlarmClockXtreme/${com.sysadmindoc.alarmclock.BuildConfig.VERSION_NAME}")
                .build()
            val body = httpClient.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "HTTP ${response.code} checking engine release" }
                response.body?.string() ?: error("Empty engine release response")
            }
            val release = JSONObject(body)
            check(!release.optBoolean("prerelease") && !release.optBoolean("draft"))
            val latest = normalizedYouTubeEngineVersion(release.optString("tag_name"))
                ?: error("Invalid engine release version")
            YouTubeEngineRelease(current, latest).also {
                releaseCheckCache = System.currentTimeMillis() to it
            }
        }.onFailure { if (it is CancellationException) throw it }
    }

    override suspend fun updateEngine(): Result<YouTubeEngineUpdateResult> = withContext(Dispatchers.IO) {
        if (!engineUpdateInFlight.compareAndSet(false, true)) {
            return@withContext Result.failure(
                IllegalStateException("Downloader engine update is already running.")
            )
        }
        try {
            runCatching<YouTubeEngineUpdateResult> {
                require(isAvailable()) {
                    "YouTube engine is still warming up. Try again in a moment."
                }
                val before = engineVersionName()
                var updateSource = "library"
                val status = try {
                    YoutubeDL.getInstance().updateYoutubeDL(
                        context,
                        YoutubeDL.UpdateChannel._STABLE
                    )
                } catch (libraryError: Exception) {
                    if (libraryError is CancellationException) throw libraryError
                    Log.d(TAG, "Library updater failed, trying manual OkHttp path", libraryError)
                    updateSource = "manual_okhttp"
                    manualUpdateViaOkHttp()
                }
                val after = engineVersionName()
                val result = when (status) {
                    YoutubeDL.UpdateStatus.DONE -> YouTubeEngineUpdateResult(
                        state = YouTubeEngineUpdateState.Updated,
                        beforeVersionName = before,
                        afterVersionName = after
                    )
                    YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> YouTubeEngineUpdateResult(
                        state = YouTubeEngineUpdateState.AlreadyCurrent,
                        beforeVersionName = before,
                        afterVersionName = after ?: before
                    )
                    else -> throw IllegalStateException("Unexpected yt-dlp update status: $status")
                }
                preferencesManager.update {
                    it.copy(
                        ytEngineActiveVersion = after ?: before ?: "",
                        ytEngineLastUpdateMs = System.currentTimeMillis(),
                        ytEngineLastUpdateStatus = result.state.name.lowercase(),
                        ytEngineLastUpdateSource = updateSource,
                        ytEngineLastFailureReason = ""
                    )
                }
                result
            }.recoverCatching { e ->
                if (e is CancellationException) throw e
                Log.w(TAG, "yt-dlp engine update failed", e)
                preferencesManager.update {
                    it.copy(
                        ytEngineLastUpdateMs = System.currentTimeMillis(),
                        ytEngineLastUpdateStatus = "failed",
                        ytEngineLastFailureReason = (e.message ?: e.javaClass.simpleName).take(200)
                    )
                }
                throw e
            }
        } finally {
            engineUpdateInFlight.set(false)
        }
    }

    override suspend fun resetEngine(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(isAvailable()) { "YouTube engine is still warming up." }
            val packagesDir = java.io.File(context.getDir("youtubedl-android", Context.MODE_PRIVATE), "yt-dlp")
            val binaryFile = java.io.File(packagesDir, "yt-dlp")
            if (binaryFile.exists()) binaryFile.delete()
            YoutubeDL.getInstance().init(context)
            val version = engineVersionName() ?: ""
            preferencesManager.update {
                it.copy(
                    ytEngineActiveVersion = version,
                    ytEngineLastUpdateMs = System.currentTimeMillis(),
                    ytEngineLastUpdateStatus = "reset",
                    ytEngineLastUpdateSource = "reset_to_bundled",
                    ytEngineLastFailureReason = ""
                )
            }
        }
    }

    /**
     * The `youtubedl-android` library's built-in updater uses bare
     * `java.net.URL.openStream()` to hit the GitHub API. GitHub rejects
     * those requests (no Accept header, inadequate UA) with 403/504,
     * surfaced as `FileNotFoundException` by Android's HttpURLConnection.
     *
     * This fallback downloads the `yt-dlp` release binary ourselves using
     * OkHttp (which sets proper headers), writes it into the library's
     * internal package directory, and returns [YoutubeDL.UpdateStatus.DONE].
     */
    private fun manualUpdateViaOkHttp(): YoutubeDL.UpdateStatus {
        val apiReq = Request.Builder()
            .url(GITHUB_RELEASES_LATEST)
            .header("Accept", "application/vnd.github.v3+json")
            .header("User-Agent", "AlarmClockXtreme/${com.sysadmindoc.alarmclock.BuildConfig.VERSION_NAME}")
            .build()
        val releaseJson = httpClient.newCall(apiReq).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("GitHub API returned ${resp.code}")
            resp.body?.string() ?: throw IllegalStateException("Empty GitHub API response")
        }
        val release = JSONObject(releaseJson)
        val remoteTag = release.optString("tag_name", "")
        val currentVersion = engineVersionName()
        if (remoteTag.isNotBlank() && remoteTag == currentVersion) {
            return YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE
        }

        val assets = release.getJSONArray("assets")
        var downloadUrl: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.getString("name") == "yt-dlp") {
                downloadUrl = asset.getString("browser_download_url")
                break
            }
        }
        if (downloadUrl == null) throw IllegalStateException("No yt-dlp asset found in release $remoteTag")

        val binaryReq = Request.Builder()
            .url(downloadUrl)
            .header("User-Agent", "AlarmClockXtreme/${com.sysadmindoc.alarmclock.BuildConfig.VERSION_NAME}")
            .build()
        val binaryBytes = httpClient.newCall(binaryReq).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("Asset download failed: ${resp.code}")
            resp.body?.bytes() ?: throw IllegalStateException("Empty asset body")
        }

        val packagesDir = java.io.File(context.getDir("youtubedl-android", Context.MODE_PRIVATE), "yt-dlp")
        packagesDir.mkdirs()
        val binaryFile = java.io.File(packagesDir, "yt-dlp")
        binaryFile.writeBytes(binaryBytes)
        binaryFile.setExecutable(true)

        Log.i(TAG, "Manual yt-dlp update: $currentVersion -> $remoteTag (${binaryBytes.size / 1024} KB)")
        return YoutubeDL.UpdateStatus.DONE
    }

    override suspend fun getPreviewStreamUrl(youtubeUrl: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(isAvailable()) { "YouTube engine still warming up" }
            require(isLikelyYouTubeUrl(youtubeUrl)) { "Invalid YouTube URL" }
            // Prefer Android-native AAC containers; use the same 60MB ceiling as Save.
            // Preview is a completed local low-bitrate file, not a signed CDN URL
            // handed to a second network stack. Bound disk use to four clips.
            val cache = java.io.File(context.cacheDir, "youtube-previews").apply { mkdirs() }
            synchronized(previewCache) {
                previewCache[youtubeUrl]?.let { cached ->
                    val file = java.io.File(cached.url)
                    if(file.isFile && System.currentTimeMillis()-cached.cachedAtMs < PREVIEW_TTL_MS)
                        return@runCatching file.absolutePath
                    previewCache.remove(youtubeUrl)
                }
            }
            val directory=java.io.File(cache, java.util.UUID.randomUUID().toString())
            check(directory.mkdirs()) { "Could not create private preview folder" }
            try {
                val request=YoutubeDLRequest(youtubeUrl).apply {
                    addOption("-f", "worstaudio[ext=m4a]/worstaudio[ext=mp4]/worstaudio[ext=webm]/worstaudio[ext=ogg]/worstaudio[ext=opus]/worstaudio[ext=mp3]")
                    addOption("--no-playlist")
                    addOption("--ignore-config")
                    addOption("--socket-timeout", "20")
                    addOption("--retries", "2")
                    addOption("--fragment-retries", "1")
                    addOption("--extractor-retries", "1")
                    addOption("--http-chunk-size", "1M")
                    addOption("--max-filesize", "60M")
                    addOption("--no-mtime")
                    addOption("--fixup", "warn")
                    addOption("-o", java.io.File(directory, "audio.%(ext)s").absolutePath)
                }
                val response = YoutubeDL.getInstance().execute(request)
                val audio = completedYouTubeAudio(directory, response.exitCode, response.out, response.err, MAX_BYTES)
                previewCache[youtubeUrl]=CachedStream(audio.absolutePath,System.currentTimeMillis())
                cache.listFiles()?.filter { it.isDirectory && it != directory }
                    ?.sortedByDescending { it.lastModified() }?.drop(3)?.forEach { it.deleteRecursively() }
                audio.absolutePath
            } catch(e: Exception) {
                directory.deleteRecursively()
                throw e
            }
        }.recoverCatching { e ->
            if(e is CancellationException) throw e
            YouTubeFailureDiagnostics.record(context,"native-preview",e)
            Log.w(TAG,"preview failed",e)
            throw e
        }
    }

    override suspend fun searchAlarmSounds(
        query: String,
        maxDurationSeconds: Int,
    ): Result<List<YouTubeSearchHit>> = withContext(Dispatchers.IO) {
        runCatching {
            require(query.isNotBlank()) { "Type a search like \"rooster crow\" or \"piano bell\"." }
            require(maxDurationSeconds > 0) { "Invalid maximum sound duration" }
            val cleanQuery = query.trim().take(200)
            try {
                val service = org.schabi.newpipe.extractor.NewPipe.getService(
                    org.schabi.newpipe.extractor.ServiceList.YouTube.serviceId
                )
                val extractor = service.getSearchExtractor(cleanQuery)
                extractor.fetchPage()
                extractor.initialPage.items
                    .filterIsInstance<org.schabi.newpipe.extractor.stream.StreamInfoItem>()
                    .filter { it.duration in 1..maxDurationSeconds.toLong() }
                    .filter { !it.name.contains('#') }
                    .take(15)
                    .map { item ->
                        YouTubeSearchHit(
                            videoUrl = item.url,
                            title = item.name,
                            uploader = item.uploaderName ?: "",
                            durationSeconds = item.duration,
                        )
                    }
            } catch (primary: Exception) {
                if (primary is CancellationException) throw primary
                YouTubeFailureDiagnostics.record(context, "newpipe-search", primary)
                Log.w(TAG, "NewPipe search failed; trying flat yt-dlp metadata", primary)
                require(isAvailable()) { "YouTube engine is still warming up. Try again in a moment." }
                try {
                    val request = YoutubeDLRequest("ytsearch30:$cleanQuery").apply {
                        addOption("--flat-playlist")
                        addOption("--dump-single-json")
                        addOption("--skip-download")
                        addOption("--socket-timeout", "15")
                        addOption("--retries", "1")
                        addOption("--extractor-retries", "1")
                        addOption("--ignore-config")
                        addOption("--no-warnings")
                    }
                    val response = YoutubeDL.getInstance().execute(request)
                    YouTubeSearchMetadata.parse(response.out ?: error("Empty search metadata"), maxDurationSeconds)
                } catch (fallback: Exception) {
                    if (fallback is CancellationException) throw fallback
                    fallback.addSuppressed(primary)
                    YouTubeFailureDiagnostics.record(context, "ytdlp-search", fallback)
                    throw fallback
                }
            }
        }.recoverCatching { e ->
            if (e is CancellationException) throw e
            YouTubeFailureDiagnostics.record(context, "search", e)
            Log.w(TAG, "search failed", e)
            throw e
        }
    }

    override suspend fun downloadAsAlarm(
        youtubeUrl: String,
        displayName: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (!isAvailable()) {
                throw IllegalStateException("YouTube downloader is still warming up. Try again in a moment.")
            }
            require(isLikelyYouTubeUrl(youtubeUrl)) {
                "That doesn't look like a YouTube URL. Paste a watch link, share link, or shorts URL."
            }

            // Keep extraction and actual transfer in the same engine, preserving its
            // client-specific headers, chunking, retries and transport. Never shell
            // out to a caller-selected downloader or write a user-controlled path.
            val directory = java.io.File(context.cacheDir, "youtube-audio-${java.util.UUID.randomUUID()}")
            check(directory.mkdirs()) { "Could not create private audio folder" }
            try {
                val request = YoutubeDLRequest(youtubeUrl).apply {
                    addOption("-f", "bestaudio")
                    addOption("--no-playlist")
                    addOption("--ignore-config")
                    addOption("--socket-timeout", "20")
                    addOption("--retries", "2")
                    addOption("--fragment-retries", "1")
                    addOption("--extractor-retries", "1")
                    addOption("--http-chunk-size", "1M")
                    addOption("--max-filesize", "60M")
                    addOption("--no-mtime")
                    addOption("--fixup", "warn")
                    addOption("-o", java.io.File(directory, "audio.%(ext)s").absolutePath)
                }
                val response = try {
                    YoutubeDL.getInstance().execute(request)
                } catch(e: Exception) {
                    if(e is CancellationException) throw e
                    YouTubeFailureDiagnostics.record(context, "native-download", e)
                    throw e
                }
                val audio = completedYouTubeAudio(directory, response.exitCode, response.out, response.err, MAX_BYTES)
                val safeName = sanitizeName(displayName).ifBlank { "youtube-alarm-${System.currentTimeMillis()}" }
                saveFileAsAlarm(audio, safeName)
            } finally {
                directory.deleteRecursively()
            }
        }.recoverCatching { e ->
            if (e is CancellationException) throw e
            YouTubeFailureDiagnostics.record(context, "audio-save", e)
            Log.w(TAG, "downloadAsAlarm failed", e)
            throw e
        }
    }

    /**
     * Copies the completed app-private audio file into MediaStore.Audio at
     * `Environment.DIRECTORY_ALARMS` with `IS_ALARM=1`, then flips
     * `IS_PENDING=0` so the system clock app + RingtoneManager pick it up.
     *
     * Returns the saved display name; throws the actual failure after cleanup.
     * Mirrors `SoundApplier.saveUrlToMediaStore` in the Aura codebase but
     * inlined and locked to ContentType.ALARM.
     */
    private fun saveFileAsAlarm(audio: java.io.File, baseName: String): String {
        if (Build.VERSION.SDK_INT <= 28 && androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Storage permission is required to save audio on Android 9 or earlier")
        }
        val resolver = context.contentResolver
        val advertised = audio.length()
        require(advertised in 1..MAX_BYTES) { "Audio file is empty or too large" }
        val extension = audio.extension.lowercase(Locale.ROOT)
        val mime = when(extension) {
            "webm" -> "audio/webm"
            "m4a", "mp4" -> "audio/mp4"
            "mp3" -> "audio/mpeg"
            "ogg", "opus" -> "audio/ogg"
            else -> throw java.io.IOException("Unsupported audio container")
        }
        return run {
            val stem = baseName.replace(Regex("(?i)\\.(m4a|mp3|ogg|webm)$"), "")
            val displayName = "$stem.$extension"
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Audio.Media.MIME_TYPE, if(extension == "webm") "audio/webm" else mime)
                put(MediaStore.Audio.Media.IS_ALARM, true)
                put(MediaStore.Audio.Media.IS_RINGTONE, false)
                put(MediaStore.Audio.Media.IS_NOTIFICATION, false)
                put(MediaStore.Audio.Media.IS_MUSIC, false)
                if (Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_ALARMS)
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                } else {
                    @Suppress("DEPRECATION")
                    val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_ALARMS)
                    if (!directory.exists() && !directory.mkdirs()) throw java.io.IOException("Could not create Alarms folder")
                    // Never overwrite a user's existing audio, even if names match.
                    var file = java.io.File(directory, displayName)
                    var suffix = 1
                    while(file.exists()) file = java.io.File(directory, "$stem-${suffix++}.$extension")
                    @Suppress("DEPRECATION")
                    put(MediaStore.Audio.Media.DATA, file.absolutePath)
                    put(MediaStore.Audio.Media.DISPLAY_NAME, file.name)
                }
            }
            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw java.io.IOException("MediaStore could not create audio file")
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    val copied = audio.inputStream().use { input ->
                        var count = 0L
                        val buf = ByteArray(64 * 1024)
                        while(true) {
                            val n = input.read(buf)
                            if(n < 0) break
                            if(n == 0) continue
                            count += n
                            if(count > MAX_BYTES) throw java.io.IOException("Audio is too large")
                            out.write(buf, 0, n)
                        }
                        count
                    }
                    if(copied == 0L || (advertised >= 0 && copied != advertised)) throw java.io.IOException("Audio transfer incomplete")
                } ?: throw java.io.IOException("MediaStore output stream unavailable")
                if(Build.VERSION.SDK_INT >= 29) {
                    val finalize = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
                    if(resolver.update(uri, finalize, null, null) != 1) throw java.io.IOException("MediaStore could not finalize audio")
                }
                values.getAsString(MediaStore.Audio.Media.DISPLAY_NAME)
            } catch(e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                if(e !is CancellationException) YouTubeFailureDiagnostics.record(context, "audio-copy", e)
                throw e
            }
        }
    }

    companion object {
        private const val TAG = "YtDlpDownloader"

        // 60 MB ceiling — covers ~30 minutes of 256 kbps AAC, more than any
        // reasonable alarm clip needs. Defends against a hostile or
        // mis-resolved CDN URL writing endlessly.
        private const val MAX_BYTES = 60L * 1024 * 1024

        // Half of YouTube's typical 6-hour signed-URL TTL.
        private const val PREVIEW_TTL_MS = 3L * 60 * 60 * 1000
        private const val GITHUB_RELEASES_LATEST =
            "https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest"

        private val URL_REGEX = Regex(
            "^https?://(www\\.|m\\.|music\\.)?(youtube\\.com|youtu\\.be|youtube-nocookie\\.com)/\\S+",
            RegexOption.IGNORE_CASE
        )

        // MediaStore display names tolerate most filename characters but slashes
        // and control chars are unsafe; collapse anything outside a safe set.
        private val UNSAFE = Regex("[^A-Za-z0-9 ._\\-()]+")

        fun isLikelyYouTubeUrl(url: String): Boolean = URL_REGEX.matches(url.trim())

        fun sanitizeName(raw: String): String =
            raw.trim()
                .replace(UNSAFE, " ")
                .replace(Regex(" +"), " ")
                .trim()                  // trim again after unsafe-char collapse
                .take(80)
                .lowercase(Locale.ROOT)
                .replace(' ', '-')
    }
}
