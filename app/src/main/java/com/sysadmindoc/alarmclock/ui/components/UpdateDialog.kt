package com.sysadmindoc.alarmclock.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sysadmindoc.alarmclock.R
import com.sysadmindoc.alarmclock.data.update.DownloadPhase
import com.sysadmindoc.alarmclock.data.update.UpdateUiState
import com.sysadmindoc.alarmclock.ui.theme.SurfaceMedium
import com.sysadmindoc.alarmclock.ui.theme.TextPrimary
import com.sysadmindoc.alarmclock.ui.theme.TextSecondary

/** Shown on app start only when a genuinely newer release exists. Download happens inside the app. */
@Composable
fun UpdateDialog(
    state: UpdateUiState,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onFullNotes: () -> Unit,
    onLater: () -> Unit
) {
    val release = state.release ?: return
    AlertDialog(
        onDismissRequest = onLater,
        containerColor = SurfaceMedium,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AppStatusChip(
                    label = stringResource(R.string.update_available_chip, release.versionName),
                    icon = Icons.Default.SystemUpdate,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.update_dialog_title),
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleLarge
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.update_whats_new_in, release.versionName),
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelLarge
                )
                release.notes.forEach { line ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("•", color = MaterialTheme.colorScheme.primary)
                        Text(line, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                when (state.phase) {
                    DownloadPhase.Downloading -> {
                        Spacer(Modifier.height(4.dp))
                        val p = state.progress
                        if (p == null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                        Text(
                            stringResource(R.string.update_downloading),
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    DownloadPhase.Failed -> Text(
                        state.error ?: stringResource(R.string.update_download_failed),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                    else -> Unit
                }
                TextButton(onClick = onFullNotes) { Text(stringResource(R.string.whats_new_full_release_notes)) }
            }
        },
        confirmButton = {
            when (state.phase) {
                DownloadPhase.Ready -> Button(onClick = onInstall) { Text(stringResource(R.string.update_install)) }
                DownloadPhase.Downloading -> Button(onClick = {}, enabled = false) {
                    Text(stringResource(R.string.update_downloading))
                }
                else -> Button(onClick = onDownload) { Text(stringResource(R.string.update_download)) }
            }
        },
        dismissButton = { TextButton(onClick = onLater) { Text(stringResource(R.string.update_later)) } }
    )
}
