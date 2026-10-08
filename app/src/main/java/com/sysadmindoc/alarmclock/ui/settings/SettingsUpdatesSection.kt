package com.sysadmindoc.alarmclock.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sysadmindoc.alarmclock.BuildConfig
import com.sysadmindoc.alarmclock.R
import com.sysadmindoc.alarmclock.data.update.DownloadPhase
import com.sysadmindoc.alarmclock.data.update.UpdateManager
import com.sysadmindoc.alarmclock.ui.theme.TextSecondary
import java.text.DateFormat
import java.util.Date

@Composable
internal fun UpdatesSection() {
    val context = LocalContext.current
    val state by UpdateManager.state.collectAsState()
    var popupOn by remember { mutableStateOf(UpdateManager.popupEnabled(context)) }
    var autoOn by remember { mutableStateOf(UpdateManager.autoDownload(context)) }
    val release = state.release
    val last = UpdateManager.lastCheckMillis(context)

    SettingsGroup(
        title = stringResource(R.string.settings_pane_updates),
        description = stringResource(R.string.settings_pane_updates_description)
    ) {
        SettingsActionRow(
            label = stringResource(R.string.updates_current_version),
            value = "v${BuildConfig.VERSION_NAME}",
            onClick = {}
        )
        val status = when {
            state.checking -> stringResource(R.string.updates_checking)
            state.error != null -> stringResource(R.string.updates_check_failed)
            release != null -> stringResource(R.string.updates_available, release.versionName)
            state.checkedOnce -> stringResource(R.string.updates_up_to_date)
            else -> stringResource(R.string.updates_not_checked)
        }
        SettingsActionRow(
            label = stringResource(R.string.updates_check_now),
            value = status,
            supportingText = if (last > 0) {
                stringResource(
                    R.string.updates_last_checked,
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(last))
                )
            } else null,
            enabled = !state.checking,
            onClick = { UpdateManager.check(context, manual = true) }
        )
        if (release != null) {
            when (state.phase) {
                DownloadPhase.Downloading -> Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val p = state.progress
                    if (p == null) LinearProgressIndicator() else LinearProgressIndicator(progress = { p })
                    Text(stringResource(R.string.update_downloading), color = TextSecondary)
                }
                DownloadPhase.Ready -> SettingsActionRow(
                    label = stringResource(R.string.update_install),
                    value = "v${release.versionName}",
                    onClick = { UpdateManager.install(context) }
                )
                else -> SettingsActionRow(
                    label = stringResource(R.string.update_download),
                    value = "v${release.versionName}",
                    supportingText = state.error,
                    onClick = { UpdateManager.startDownload(context) }
                )
            }
        }
        SettingsToggle(
            label = stringResource(R.string.updates_popup_toggle),
            supportingText = stringResource(R.string.updates_popup_toggle_description),
            checked = popupOn,
            onToggle = { popupOn = it; UpdateManager.setPopupEnabled(context, it) }
        )
        SettingsToggle(
            label = stringResource(R.string.updates_auto_download_toggle),
            supportingText = stringResource(R.string.updates_auto_download_toggle_description),
            checked = autoOn,
            onToggle = { autoOn = it; UpdateManager.setAutoDownload(context, it) }
        )
    }
}
