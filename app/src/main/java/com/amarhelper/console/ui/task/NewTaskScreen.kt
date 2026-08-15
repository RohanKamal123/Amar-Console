package com.amarhelper.console.ui.task

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amarhelper.console.R
import com.amarhelper.console.domain.model.AgentProvider
import com.amarhelper.console.ui.components.EmptyState
import com.amarhelper.console.ui.components.ErrorState
import com.amarhelper.console.ui.components.LoadingState
import com.amarhelper.console.ui.components.MinTouchTarget

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewTaskScreen(
    onBack: () -> Unit,
    onTaskStarted: (AgentProvider, String) -> Unit,
    viewModel: NewTaskViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.startedSession) {
        state.startedSession?.let { onTaskStarted(it.provider, it.id) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New task") },
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
        when {
            state.isLoadingProviders -> LoadingState(
                message = "Looking for agents…",
                modifier = Modifier.padding(padding),
            )

            state.providers.isEmpty() -> EmptyState(
                title = "No agent configured",
                description = "Set an OpenHands or OpenCode URL in Settings before starting a task.",
                modifier = Modifier.padding(padding),
            )

            state.error != null -> ErrorState(
                error = state.error!!,
                onRetry = viewModel::submit,
                modifier = Modifier.padding(padding),
            )

            else -> Column(
                modifier = Modifier
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Agent",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.providers.forEach { provider ->
                            FilterChip(
                                selected = state.selectedProvider == provider,
                                onClick = { viewModel.onProviderSelected(provider) },
                                label = { Text(provider.displayName) },
                                modifier = Modifier.heightIn(min = 40.dp),
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = state.prompt,
                    onValueChange = viewModel::onPromptChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 180.dp)
                        .semantics { contentDescription = "Task description" },
                    label = { Text("What should the agent do?") },
                    placeholder = { Text("Build a REST API for user authentication.") },
                    supportingText = {
                        Text(state.validationMessage ?: "${state.prompt.length} characters")
                    },
                    isError = state.validationMessage != null,
                )

                if (state.selectedProvider == AgentProvider.OPEN_HANDS) {
                    OutlinedTextField(
                        value = state.repository,
                        onValueChange = viewModel::onRepositoryChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Repository (optional)") },
                        placeholder = { Text("owner/name") },
                    )
                }

                Button(
                    onClick = viewModel::submit,
                    enabled = state.canSubmit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp),
                ) {
                    if (state.isSubmitting) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Text("  Submitting…")
                    } else {
                        Icon(Icons.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("  Start task")
                    }
                }

                Text(
                    text = "The task runs on your own infrastructure. Output streams into the session console.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }
    }
}
