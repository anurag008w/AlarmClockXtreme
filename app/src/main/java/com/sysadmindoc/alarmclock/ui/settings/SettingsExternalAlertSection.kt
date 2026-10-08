package com.sysadmindoc.alarmclock.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.sysadmindoc.alarmclock.R
import com.sysadmindoc.alarmclock.platform.ExternalAlertStore
import com.sysadmindoc.alarmclock.service.ExternalAlertService
import java.text.DateFormat
import java.util.Date

/**
 * Settings card for the opt-in external alert receiver: switch, pairing code,
 * a local 5 second test and the last alert received. State lives in
 * [ExternalAlertStore], so no view model plumbing is needed.
 */
@Composable
internal fun ExternalAlertSection() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var alarmControl by remember { mutableStateOf(ExternalAlertStore.isAlarmControlEnabled(context)) }
    var enabled by remember { mutableStateOf(ExternalAlertStore.isEnabled(context)) }
    var code by remember {
        mutableStateOf(
            if (enabled || ExternalAlertStore.isAlarmControlEnabled(context)) ExternalAlertStore.pairingCode(context) else ""
        )
    }
    var lastAt by remember { mutableStateOf(ExternalAlertStore.lastAt(context)) }
    val copiedText = stringResource(R.string.ext_alert_copied)

    SettingsGroup(
        title = stringResource(R.string.ext_alert_settings_title),
        description = stringResource(R.string.ext_alert_settings_description)
    ) {
        SettingsToggle(
            label = stringResource(R.string.ext_alert_enable),
            checked = enabled,
            supportingText = stringResource(R.string.ext_alert_enable_description),
            onToggle = { on ->
                ExternalAlertStore.setEnabled(context, on)
                enabled = on
                code = if (on || alarmControl) ExternalAlertStore.pairingCode(context) else ""
            }
        )
        SettingsToggle(
            label = stringResource(R.string.ext_alarm_control_enable),
            checked = alarmControl,
            supportingText = stringResource(R.string.ext_alarm_control_description),
            onToggle = { on ->
                ExternalAlertStore.setAlarmControlEnabled(context, on)
                alarmControl = on
                if (on && code.isEmpty()) code = ExternalAlertStore.pairingCode(context)
            }
        )
        if (enabled || alarmControl) {
            Text(
                text = stringResource(R.string.ext_alert_code_label),
                style = MaterialTheme.typography.labelLarge
            )
            Text(
                text = code,
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = stringResource(R.string.ext_alert_code_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = {
                    clipboard.setText(AnnotatedString(code))
                    Toast.makeText(context, copiedText, Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.ext_alert_copy)) }
                OutlinedButton(onClick = {
                    ExternalAlertStore.regenerate(context)
                    code = ExternalAlertStore.pairingCode(context)
                }) { Text(stringResource(R.string.ext_alert_new_code)) }
            }
            OutlinedButton(onClick = {
                ExternalAlertService.start(context, "settings-test", "vibrate", "", 5)
            }) { Text(stringResource(R.string.ext_alert_test)) }
            val summary = ExternalAlertStore.lastSummary(context)
            Text(
                text = if (lastAt > 0L) {
                    stringResource(
                        R.string.ext_alert_last,
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                            .format(Date(lastAt)) + if (summary.isBlank()) "" else " - $summary"
                    )
                } else {
                    stringResource(R.string.ext_alert_none)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.ext_alert_limits),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
