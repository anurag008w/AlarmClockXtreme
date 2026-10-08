package com.sysadmindoc.alarmclock

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.cloud.CloudSyncManager
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.data.share.AlarmShareCodec
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import com.sysadmindoc.alarmclock.service.AlarmService
import com.sysadmindoc.alarmclock.ui.alarmfiring.AlarmFiringActivity
import com.sysadmindoc.alarmclock.ui.components.WhatsNewDialog
import com.sysadmindoc.alarmclock.ui.navigation.AppNavigation
import com.sysadmindoc.alarmclock.ui.theme.AlarmClockXtremeTheme
import com.sysadmindoc.alarmclock.util.WhatsNewNotes
import com.sysadmindoc.alarmclock.util.WhatsNewTracker
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var preferencesManager: PreferencesManager

    @Inject
    lateinit var cloudSyncManager: CloudSyncManager

    private var lastHandledShareTokenKey: String? = null
    private var pendingSharedAlarmToken: String? = null
    private var pendingSharedAlarmDraft by mutableStateOf<Alarm?>(null)
    private var foregroundCloudSyncJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        lastHandledShareTokenKey = savedInstanceState?.getString(KEY_LAST_HANDLED_SHARE_TOKEN_KEY)
        pendingSharedAlarmToken = savedInstanceState?.getString(KEY_PENDING_SHARE_TOKEN)
        pendingSharedAlarmToken?.let { restorePendingSharedAlarm(it) } ?: handleSharedAlarmIntent(intent)
        // v1.5.0: Decide once at launch whether to surface the What's-new
        // dialog; avoid re-checking during recomposition.
        val showWhatsNew = WhatsNewTracker.shouldShow(this, BuildConfig.VERSION_CODE)
        com.sysadmindoc.alarmclock.data.update.UpdateManager.check(this, manual = false)

        setContent {
            val settings = preferencesManager.settings.collectAsStateWithLifecycle(
                initialValue = AppSettings()
            )
            AlarmClockXtremeTheme(
                accentColorHex = settings.value.accentColor,
                dynamicColor = settings.value.dynamicColorEnabled,
                expressiveMode = settings.value.expressiveModeEnabled,
                reduceMotionAndFlashing = settings.value.reduceMotionAndFlashing
            ) {
                AppNavigation(
                    sharedAlarmDraft = pendingSharedAlarmDraft,
                    onSharedAlarmConsumed = {
                        pendingSharedAlarmDraft = null
                        pendingSharedAlarmToken = null
                    }
                )

                val updateState by com.sysadmindoc.alarmclock.data.update.UpdateManager.state.collectAsStateWithLifecycle()
                if (com.sysadmindoc.alarmclock.data.update.UpdateManager.shouldShowPopup(this@MainActivity, updateState)) {
                    com.sysadmindoc.alarmclock.ui.components.UpdateDialog(
                        state = updateState,
                        onDownload = { com.sysadmindoc.alarmclock.data.update.UpdateManager.startDownload(this@MainActivity) },
                        onInstall = { com.sysadmindoc.alarmclock.data.update.UpdateManager.install(this@MainActivity) },
                        onFullNotes = { openUrl(updateState.release?.pageUrl ?: WhatsNewNotes.REPO_URL) },
                        onLater = { com.sysadmindoc.alarmclock.data.update.UpdateManager.dismissPopup() }
                    )
                }

                var dialogVisible by remember { mutableStateOf(showWhatsNew) }
                if (dialogVisible) {
                    WhatsNewDialog(
                        version = BuildConfig.VERSION_NAME,
                        highlights = WhatsNewNotes.forVersion(BuildConfig.VERSION_NAME),
                        onOpenReleaseNotes = {
                            dialogVisible = false
                            WhatsNewTracker.markShown(this@MainActivity, BuildConfig.VERSION_CODE)
                            openUrl(WhatsNewNotes.releaseNotesUrl(BuildConfig.VERSION_NAME))
                        },
                        onOpenRoadmap = {
                            dialogVisible = false
                            WhatsNewTracker.markShown(this@MainActivity, BuildConfig.VERSION_CODE)
                            openRoadmap()
                        },
                        onDismiss = {
                            dialogVisible = false
                            WhatsNewTracker.markShown(this@MainActivity, BuildConfig.VERSION_CODE)
                        }
                    )
                }
            }
        }
    }

    private fun openRoadmap() = openUrl(WhatsNewNotes.ROADMAP_URL)

    private fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        runCatching { startActivity(intent) }
            .onFailure {
                Toast.makeText(
                    this,
                    getString(R.string.roadmap_link_failed),
                    Toast.LENGTH_SHORT
                ).show()
            }
    }

    override fun onResume() {
        super.onResume()

        // Lightweight foreground watchdog: pull web-side edits every couple
        // of seconds while the Android app is open. WorkManager remains the
        // background fallback when the process is not alive.
        foregroundCloudSyncJob?.cancel()
        // Dispatchers.IO is deliberate: sync serializes Room rows with Moshi
        // and parses sync metadata on every pass. On the main thread that
        // CPU work froze low-end devices during ordinary app use.
        foregroundCloudSyncJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                runCatching { cloudSyncManager.syncNow() }
                delay(2_000L)
            }
        }


        val snapshot = AlarmService.activeAlarm.get() ?: return
        val intent = Intent(this, AlarmFiringActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, snapshot.alarmId)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, snapshot.scheduledAt)
            putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, snapshot.fireId)
        }
        startActivity(intent)
    }

    override fun onPause() {
        foregroundCloudSyncJob?.cancel()
        foregroundCloudSyncJob = null
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedAlarmIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_LAST_HANDLED_SHARE_TOKEN_KEY, lastHandledShareTokenKey)
        outState.putString(KEY_PENDING_SHARE_TOKEN, pendingSharedAlarmToken)
        super.onSaveInstanceState(outState)
    }

    private fun handleSharedAlarmIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != AlarmShareCodec.SCHEME || uri.host != AlarmShareCodec.HOST) return

        val token = uri.getQueryParameter(AlarmShareCodec.DATA_PARAM).orEmpty()
        if (token.isBlank()) return
        val tokenKey = AlarmShareCodec.tokenStorageKey(token)
        if (tokenKey == lastHandledShareTokenKey) return
        lastHandledShareTokenKey = tokenKey

        queueSharedAlarmDraft(token, showReadyToast = true)
    }

    private fun restorePendingSharedAlarm(token: String) {
        queueSharedAlarmDraft(token, showReadyToast = false)
    }

    private fun queueSharedAlarmDraft(token: String, showReadyToast: Boolean) {
        val decoded = AlarmShareCodec.decodeToken(token)
        decoded.fold(
            onSuccess = { alarm ->
                pendingSharedAlarmToken = token
                pendingSharedAlarmDraft = AlarmShareCodec.prepareImportedAlarm(
                    alarm = alarm,
                    defaultLabel = getString(R.string.share_default_alarm_label)
                )
                if (showReadyToast) {
                    Toast.makeText(
                        this,
                        getString(R.string.share_review_before_saving),
                        Toast.LENGTH_LONG
                    ).show()
                }
            },
            onFailure = {
                pendingSharedAlarmToken = null
                pendingSharedAlarmDraft = null
                Toast.makeText(
                    this,
                    getString(R.string.share_import_failed),
                    Toast.LENGTH_LONG
                ).show()
            }
        )
    }

    companion object {
        private const val KEY_LAST_HANDLED_SHARE_TOKEN_KEY = "last_handled_share_token_key"
        private const val KEY_PENDING_SHARE_TOKEN = "pending_share_token"
    }
}
