package com.amarhelper.console.ui.sessions

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amarhelper.console.R
import com.amarhelper.console.domain.model.AgentProvider
import com.amarhelper.console.domain.model.AgentSession
import com.amarhelper.console.ui.components.EmptyState
import com.amarhelper.console.ui.components.ErrorState
import com.amarhelper.console.ui.components.LoadingState
import com.amarhelper.console.ui.components.MinTouchTarget
import com.amarhelper.console.ui.components.TaskStatePill
import com.amarhelper.console.ui.components.relativeTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    onBack: () -> Unit,
    onOpenSession: (AgentProvider, String) -> Unit,
    onNewTask: () -> Unit,
    viewModel: SessionsViewModel = hiltViewModel(),
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
                title = { Text("Sessions") },
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
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            when {
                state.isLoading -> LoadingState("Loading sessions…")

                state.error != null && state.sessions.isEmpty() -> ErrorState(
                    error = state.error!!,
                    onRetry = viewModel::refresh,
                )

                state.sessions.isEmpty() -> EmptyState(
                    title = "No sessions",
                    description = "Start a task and it will show up here.",
                    action = {
                        Button(onClick = onNewTask, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                            Text("New task")
                        }
                    },
                )

                else -> LazyColumn(
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                ) {
                    if (state.active.isNotEmpty()) {
                        item { GroupLabel("Active") }
                        items(state.active, key = { it.provider.name + it.id }) { session ->
                            SessionRow(
                                session = session,
                                onOpen = { onOpenSession(session.provider, session.id) },
                                onDelete = { viewModel.delete(session.provider, session.id) },
                            )
                        }
                    }
                    if (state.previous.isNotEmpty()) {
                        item { GroupLabel("Previous") }
                        items(state.previous, key = { it.provider.name + it.id }) { session ->
                            SessionRow(
                                session = session,
                                onOpen = { onOpenSession(session.provider, session.id) },
                                onDelete = { viewModel.delete(session.provider, session.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SessionRow(
    session: AgentSession,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = session.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDelete, modifier = Modifier.size(MinTouchTarget)) {
                Icon(
                    Icons.Filled.DeleteOutline,
                    contentDescription = "Delete session ${session.title}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            TaskStatePill(session.state)
            Text(
                text = "${session.provider.displayName} · created ${relativeTime(session.createdAtEpochMillis)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(
            modifier = Modifier.padding(top = 12.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}
