package com.sysadmindoc.alarmclock.data.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import com.sysadmindoc.alarmclock.BuildConfig
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class DownloadPhase { Idle, Downloading, Ready, Failed }

/** Failure reasons; the UI maps each to a string resource. */
enum class UpdateError { Network, Storage, Removed, Failed, Incomplete }

data class UpdateUiState(
    val checking: Boolean = false,
    val release: ReleaseInfo? = null,
    val checkedOnce: Boolean = false,
    val error: UpdateError? = null,
    val phase: DownloadPhase = DownloadPhase.Idle,
    /** 0f..1f, or null while the size is not known yet. */
    val progress: Float? = null,
    val popupDismissed: Boolean = false
)

/**
 * In-app update flow. Checks anurag008w/AlarmClockXtreme releases, downloads the
 * APK with the system DownloadManager (no browser), then hands the file to the
 * package installer. Only the play flavor ships this; the F-Droid build is
 * updated by F-Droid.
 */
object UpdateManager {
    val isSupported: Boolean get() = BuildConfig.FLAVOR == "play"

    private const val PREFS = "update_prefs"
    private const val KEY_POPUP = "popup_enabled"
    private const val KEY_AUTO = "auto_download"
    private const val KEY_LAST_CHECK = "last_check_millis"
    private const val KEY_DOWNLOAD_ID = "download_id"
    private const val KEY_DOWNLOAD_VERSION = "download_version"
    private const val RECHECK_GUARD_MS = 5L * 60 * 1000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pollJob: Job? = null
    private val _state = MutableStateFlow(UpdateUiState())
    val state: StateFlow<UpdateUiState> = _state

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun popupEnabled(c: Context) = prefs(c).getBoolean(KEY_POPUP, true)
    fun setPopupEnabled(c: Context, v: Boolean) = prefs(c).edit().putBoolean(KEY_POPUP, v).apply()
    fun autoDownload(c: Context) = prefs(c).getBoolean(KEY_AUTO, false)
    fun setAutoDownload(c: Context, v: Boolean) = prefs(c).edit().putBoolean(KEY_AUTO, v).apply()
    fun lastCheckMillis(c: Context) = prefs(c).getLong(KEY_LAST_CHECK, 0L)

    fun dismissPopup() = _state.update { it.copy(popupDismissed = true) }

    /** Popup is shown only for a genuinely newer release, when the user has not turned it off. */
    fun shouldShowPopup(c: Context, s: UpdateUiState): Boolean =
        isSupported && popupEnabled(c) && s.release != null && !s.popupDismissed

    /** App-start check, throttled. Manual checks from Settings pass manual = true. */
    fun check(context: Context, manual: Boolean) {
        if (!isSupported) return
        val app = context.applicationContext
        if (_state.value.checking) return
        if (!manual) {
            // Launch check: runs on every cold start while the popup or auto-download
            // is on. Only skipped when this process already checked a moment ago
            // (rotation / activity recreate), never because of a check from an earlier run.
            if (!popupEnabled(app) && !autoDownload(app)) return
            if (_state.value.checkedOnce && System.currentTimeMillis() - lastCheckMillis(app) < RECHECK_GUARD_MS) return
        }
        _state.update { it.copy(checking = true, error = null) }
        scope.launch {
            try {
                val latest = UpdateChecker.fetchLatest()
                prefs(app).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
                val newer = latest?.takeIf { VersionCompare.isNewer(it.versionName, BuildConfig.VERSION_NAME) }
                _state.update { it.copy(checking = false, checkedOnce = true, release = newer, error = null) }
                if (newer != null) {
                    restoreDownloadState(app, newer)
                    if (autoDownload(app) && _state.value.phase == DownloadPhase.Idle) {
                        startDownload(app, wifiOnly = true)
                    }
                }
            } catch (e: Exception) {
                _state.update { it.copy(checking = false, checkedOnce = true, error = UpdateError.Network) }
            }
        }
    }

    private fun apkFile(c: Context, version: String): File? =
        c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { File(it, "AlarmClockXtreme-$version.apk") }

    private fun restoreDownloadState(c: Context, release: ReleaseInfo) {
        val p = prefs(c)
        if (p.getString(KEY_DOWNLOAD_VERSION, null) != release.versionName) return
        val file = apkFile(c, release.versionName) ?: return
        val id = p.getLong(KEY_DOWNLOAD_ID, -1L)
        if (file.exists() && (release.apkSize <= 0 || file.length() == release.apkSize)) {
            _state.update { it.copy(phase = DownloadPhase.Ready, progress = 1f) }
        } else if (id != -1L) {
            beginPolling(c, id, release)
        }
    }

    fun startDownload(context: Context, wifiOnly: Boolean = false) {
        val app = context.applicationContext
        val release = _state.value.release ?: return
        if (_state.value.phase == DownloadPhase.Downloading) return
        val file = apkFile(app, release.versionName)
        if (file == null) {
            _state.update { it.copy(phase = DownloadPhase.Failed, error = UpdateError.Storage) }
            return
        }
        file.delete()
        val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val req = DownloadManager.Request(Uri.parse(release.apkUrl))
            .setTitle("AlarmClockXtreme ${release.versionName}")
            .setDescription("Downloading update")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, file.name)
        if (wifiOnly) req.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
        val id = dm.enqueue(req)
        prefs(app).edit().putLong(KEY_DOWNLOAD_ID, id).putString(KEY_DOWNLOAD_VERSION, release.versionName).apply()
        _state.update { it.copy(phase = DownloadPhase.Downloading, progress = null, error = null) }
        beginPolling(app, id, release)
    }

    private fun beginPolling(c: Context, id: Long, release: ReleaseInfo) {
        pollJob?.cancel()
        _state.update { it.copy(phase = DownloadPhase.Downloading) }
        pollJob = scope.launch {
            val dm = c.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            while (true) {
                dm.query(DownloadManager.Query().setFilterById(id))?.use { cur ->
                    if (!cur.moveToFirst()) {
                        _state.update { it.copy(phase = DownloadPhase.Failed, error = UpdateError.Removed) }
                        return@launch
                    }
                    val status = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val done = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val total = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            val file = apkFile(c, release.versionName)
                            val sizeOk = file != null && file.exists() &&
                                (release.apkSize <= 0 || file.length() == release.apkSize)
                            _state.update {
                                if (sizeOk) it.copy(phase = DownloadPhase.Ready, progress = 1f)
                                else it.copy(phase = DownloadPhase.Failed, error = UpdateError.Incomplete)
                            }
                            return@launch
                        }
                        DownloadManager.STATUS_FAILED -> {
                            _state.update { it.copy(phase = DownloadPhase.Failed, error = UpdateError.Failed) }
                            return@launch
                        }
                        else -> {
                            val p = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
                            _state.update { it.copy(progress = p) }
                        }
                    }
                }
                delay(500)
            }
        }
    }

    fun canInstall(c: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || c.packageManager.canRequestPackageInstalls()

    /** Opens the system "install unknown apps" screen for this app. */
    fun openInstallPermissionSettings(c: Context) {
        val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { c.startActivity(i) }
    }

    /** Hands the downloaded APK to the system installer. Returns false when permission is still needed. */
    fun install(context: Context): Boolean {
        val release = _state.value.release ?: return false
        val file = apkFile(context, release.versionName)?.takeIf { it.exists() } ?: return false
        if (!canInstall(context)) {
            openInstallPermissionSettings(context)
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(i) }.isSuccess
    }
}
