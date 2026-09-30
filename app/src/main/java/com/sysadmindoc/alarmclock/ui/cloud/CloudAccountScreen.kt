package com.sysadmindoc.alarmclock.ui.cloud

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import com.sysadmindoc.alarmclock.R
import com.sysadmindoc.alarmclock.data.cloud.CloudSyncManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import retrofit2.HttpException
import javax.inject.Inject

data class CloudAccountUiState(
    val loggedIn: Boolean = false,
    val email: String = "",
    val busy: Boolean = false,
    val message: String = "",
    val aiMessage: String = ""
)

@HiltViewModel
class CloudAccountViewModel @Inject constructor(
    private val syncManager: CloudSyncManager,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val _ui = MutableStateFlow(
        CloudAccountUiState(loggedIn = syncManager.isLoggedIn(), email = syncManager.email())
    )
    val ui = _ui.asStateFlow()

    fun login(email: String, password: String) = submit { syncManager.login(email, password) }
    fun register(email: String, password: String) = submit { syncManager.register(email, password) }

    fun sync() {
        viewModelScope.launch(Dispatchers.IO) {
            _ui.value = _ui.value.copy(busy = true, message = "")
            syncManager.syncNow()
                .onSuccess {
                    _ui.value = _ui.value.copy(
                        busy = false,
                        message = context.getString(R.string.cloud_sync_complete)
                    )
                }
                .onFailure {
                    _ui.value = _ui.value.copy(
                        busy = false,
                        message = cloudErrorMessage(it)
                    )
                }
        }
    }

    fun runAi(command: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _ui.value = _ui.value.copy(busy = true, aiMessage = "")
            syncManager.sendAiCommand(command)
                .onSuccess {
                    _ui.value = _ui.value.copy(
                        busy = false,
                        aiMessage = it
                    )
                }
                .onFailure {
                    _ui.value = _ui.value.copy(
                        busy = false,
                        aiMessage = cloudErrorMessage(it)
                    )
                }
        }
    }

    fun logout() {
        syncManager.logout()
        _ui.value = CloudAccountUiState()
    }

    private fun submit(block: suspend () -> Result<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            _ui.value = _ui.value.copy(busy = true, message = "")
            block()
                .onSuccess { email ->
                    _ui.value = CloudAccountUiState(
                        loggedIn = true,
                        email = email,
                        message = context.getString(R.string.cloud_account_connected)
                    )
                }
                .onFailure {
                    _ui.value = _ui.value.copy(
                        busy = false,
                        message = cloudErrorMessage(it)
                    )
                }
        }
    }

    private fun cloudErrorMessage(error: Throwable): String {
        if (error is HttpException) {
            val body = runCatching {
                error.response()?.errorBody()?.string().orEmpty()
            }.getOrDefault("")

            val detail = runCatching {
                val json = JSONObject(body)
                when (val value = json.opt("detail")) {
                    is String -> value
                    is org.json.JSONArray -> value.toString()
                    else -> ""
                }
            }.getOrDefault("")

            if (detail.isNotBlank()) {
                return "HTTP " + error.code() + ": " + detail.replace('_', ' ')
            }
            return "HTTP " + error.code() + ": " + context.getString(R.string.cloud_request_failed)
        }

        return error.message?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.cloud_request_failed)
    }
}

@Composable
fun CloudAccountScreen(
    onNavigateBack: () -> Unit = {},
    viewModel: CloudAccountViewModel = androidx.hilt.navigation.compose.hiltViewModel()
) {
    val state by viewModel.ui.collectAsState()
    var email by remember { mutableStateOf(state.email) }
    var password by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }
    var registerMode by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.cloud_account_title), style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onNavigateBack, enabled = !state.busy) {
                Text(
                    text = stringResource(R.string.cloud_account_back),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        if (!state.loggedIn) {
            OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.cloud_email)) })
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.cloud_password)) },
                visualTransformation = PasswordVisualTransformation()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { if (registerMode) viewModel.register(email, password) else viewModel.login(email, password) },
                    enabled = !state.busy && email.isNotBlank() && password.length >= 8
                ) { Text(if (registerMode) stringResource(R.string.cloud_create_account) else stringResource(R.string.cloud_login)) }
                OutlinedButton(onClick = { registerMode = !registerMode }, enabled = !state.busy) {
                    Text(if (registerMode) stringResource(R.string.cloud_use_login) else stringResource(R.string.cloud_use_register))
                }
            }
        } else {
            Text(state.email, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.cloud_connected), color = MaterialTheme.colorScheme.primary)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = viewModel::sync, enabled = !state.busy) { Text(stringResource(R.string.cloud_sync_now)) }
                OutlinedButton(onClick = viewModel::logout, enabled = !state.busy) { Text(stringResource(R.string.cloud_logout)) }
            }

            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.cloud_ai_title), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                command,
                { command = it },
                Modifier.fillMaxWidth(),
                minLines = 3,
                label = { Text(stringResource(R.string.cloud_ai_command)) }
            )
            Button(onClick = { viewModel.runAi(command) }, enabled = !state.busy && command.isNotBlank()) {
                Text(stringResource(R.string.cloud_ai_run))
            }
            if (state.aiMessage.isNotBlank()) Text(state.aiMessage)
        }

        if (state.busy) CircularProgressIndicator()
        if (state.message.isNotBlank()) Text(state.message)
        OutlinedButton(onClick = onNavigateBack) { Text(stringResource(R.string.cloud_account_back)) }
    }
}
