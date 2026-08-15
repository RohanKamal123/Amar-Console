package com.amarhelper.console.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amarhelper.console.BuildConfig
import com.amarhelper.console.R
import com.amarhelper.console.data.config.Environment
import com.amarhelper.console.data.config.ServiceId
import com.amarhelper.console.data.config.ThemeMode
import com.amarhelper.console.ui.components.MinTouchTarget
import com.amarhelper.console.ui.components.relativeTime

/** Lets instrumented tests scroll the settings list to a service that is below the fold. */
const val SETTINGS_LIST_TAG = "settings_list"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissNotice()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(MinTouchTarget)) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .testTag(SETTINGS_LIST_TAG),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item { SectionHeader("Environment") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Environment.entries.forEach { environment ->
                        FilterChip(
                            selected = state.config.environment == environment,
                            onClick = { viewModel.setEnvironment(environment) },
                            label = { Text(environment.label) },
                            modifier = Modifier.heightIn(min = 40.dp),
                        )
                    }
                }
            }

            item { SectionHeader("Services") }
            ServiceId.entries.forEach { service ->
                item(key = "service-${service.name}") {
                    ServiceSettings(
                        service = service,
                        healthPath = state.config.liteLlmHealthPath,
                        onHealthPathChange = viewModel::setLiteLlmHealthPath,
                        url = state.urlDrafts[service].orEmpty(),
                        urlError = state.urlErrors[service],
                        urlWarning = state.urlWarnings[service],
                        credentialSet = state.credentialSet[service] == true,
                        credentialUpdatedAt = state.credentialUpdatedAt[service],
                        test = state.tests[service] ?: ConnectionTest.Idle,
                        onUrlChange = { viewModel.onUrlChanged(service, it) },
                        onSaveUrl = { viewModel.saveUrl(service) },
                        onSaveToken = { viewModel.saveToken(service, it) },
                        onClearToken = { viewModel.clearToken(service) },
                        onTest = { viewModel.testConnection(service) },
                    )
                }
            }

            item { SectionHeader("Appearance") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = state.config.themeMode == mode,
                            onClick = { viewModel.setThemeMode(mode) },
                            label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) },
                            modifier = Modifier.heightIn(min = 40.dp),
                        )
                    }
                }
            }

            item { SectionHeader("Polling") }
            item {
                PollIntervalRow(
                    seconds = state.config.pollIntervalSeconds,
                    onChange = viewModel::setPollInterval,
                )
            }

            item { SectionHeader("Security") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Credentials are encrypted with a key held in the Android Keystore and " +
                            "are never displayed after saving.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = viewModel::clearAllCredentials,
                        modifier = Modifier.heightIn(min = MinTouchTarget),
                    ) { Text("Sign out of all services") }
                }
            }

            item { SectionHeader("Diagnostics") }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Verbose network logging", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = if (BuildConfig.VERBOSE_LOGGING) {
                                "Logs request bodies to logcat. Authorization headers are always redacted."
                            } else {
                                "Disabled in release builds."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.config.verboseNetworkLogging,
                        onCheckedChange = viewModel::setVerboseLogging,
                        enabled = BuildConfig.VERBOSE_LOGGING,
                    )
                }
            }

            item { SectionHeader("About") }
            item {
                Column(modifier = Modifier.padding(bottom = 40.dp)) {
                    AboutRow("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    AboutRow("Package", BuildConfig.APPLICATION_ID)
                    AboutRow("Build type", if (BuildConfig.DEBUG) "debug" else "release")
                    Text(
                        text = "Amar Console talks only to the endpoints you configure. " +
                            "No telemetry leaves the device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun ServiceSettings(
    service: ServiceId,
    healthPath: String,
    onHealthPathChange: (String) -> Unit,
    url: String,
    urlError: String?,
    urlWarning: String?,
    credentialSet: Boolean,
    credentialUpdatedAt: Long?,
    test: ConnectionTest,
    onUrlChange: (String) -> Unit,
    onSaveUrl: () -> Unit,
    onSaveToken: (String) -> Unit,
    onClearToken: () -> Unit,
    onTest: () -> Unit,
) {
    var tokenDraft by rememberSaveable(service) { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = service.displayName,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )

        OutlinedTextField(
            value = url,
            onValueChange = onUrlChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Base URL") },
            placeholder = { Text("https://host.tailnet-name.ts.net:4096") },
            isError = urlError != null,
            supportingText = {
                val message = urlError ?: urlWarning
                if (message != null) {
                    Text(
                        text = message,
                        color = if (urlError != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
            ),
        )

        OutlinedTextField(
            value = tokenDraft,
            onValueChange = { tokenDraft = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(if (credentialSet) "Replace credential" else "Token or API key (optional)") },
            visualTransformation = PasswordVisualTransformation(),
            supportingText = {
                Text(
                    text = if (credentialSet) {
                        "Saved ${relativeTime(credentialUpdatedAt)} · stored encrypted, never shown"
                    } else {
                        "Leave empty if this service needs no authentication"
                    },
                )
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
        )

        if (service == ServiceId.LITE_LLM) {
            var pathDraft by rememberSaveable(healthPath) { mutableStateOf(healthPath) }
            OutlinedTextField(
                value = pathDraft,
                onValueChange = { pathDraft = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Health path") },
                supportingText = {
                    Text(
                        "Probed to decide online/offline. The upstream proxy serves " +
                            "health/readiness; a custom router may expose something else.",
                    )
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )
            if (pathDraft.trim().trim('/') != healthPath) {
                TextButton(
                    onClick = { onHealthPathChange(pathDraft) },
                    modifier = Modifier.heightIn(min = MinTouchTarget),
                ) { Text("Save health path") }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    onSaveUrl()
                    if (tokenDraft.isNotBlank()) {
                        onSaveToken(tokenDraft)
                        tokenDraft = ""
                    }
                },
                modifier = Modifier.heightIn(min = MinTouchTarget),
            ) { Text("Save") }

            OutlinedButton(
                onClick = onTest,
                enabled = url.isNotBlank() && test != ConnectionTest.Running,
                modifier = Modifier.heightIn(min = MinTouchTarget),
            ) { Text(if (test == ConnectionTest.Running) "Testing…" else "Test") }

            if (credentialSet) {
                TextButton(
                    onClick = onClearToken,
                    modifier = Modifier.heightIn(min = MinTouchTarget),
                ) { Text("Clear key") }
            }
        }

        when (test) {
            is ConnectionTest.Passed -> Text(
                text = buildString {
                    append("Connected")
                    test.latencyMillis?.let { append(" in ${it} ms") }
                    test.version?.let { append(" · v$it") }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            is ConnectionTest.Failed -> Text(
                text = test.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            else -> Unit
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun PollIntervalRow(seconds: Int, onChange: (Int) -> Unit) {
    Column {
        Text(
            text = "Status refresh every $seconds s",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = "Used for providers without an event stream. Lower values cost battery and data.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(2, 5, 10, 30).forEach { option ->
                FilterChip(
                    selected = seconds == option,
                    onClick = { onChange(option) },
                    label = { Text("${option}s") },
                    modifier = Modifier.heightIn(min = 40.dp),
                )
            }
        }
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

