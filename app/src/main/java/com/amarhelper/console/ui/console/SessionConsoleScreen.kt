package com.amarhelper.console.ui.console

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.amarhelper.console.ui.components.EmptyState
import com.amarhelper.console.ui.components.ErrorState
import com.amarhelper.console.ui.components.LoadingState
import com.amarhelper.console.ui.components.MinTouchTarget
import com.amarhelper.console.ui.components.TaskStatePill

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionConsoleScreen(
    provider: AgentProvider,
    sessionId: String,
    onBack: () -> Unit,
    viewModel: SessionConsoleViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(provider, sessionId) {
        viewModel.start(provider, sessionId)
    }

    // Follow the tail only while the user is already near it, so scrolling back through
    // output is not yanked away by new lines.
    LaunchedEffect(state.events.size) {
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (state.events.isNotEmpty() && lastVisible >= state.events.size - 3) {
            listState.animateScrollToItem(state.events.lastIndex.coerceAtLeast(0))
        }
    }

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
                title = {
                    Column {
                        Text(
                            text = state.session?.title ?: "Session",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${provider.displayName} · ${sessionId.take(12)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(MinTouchTarget)) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::loadSession, modifier = Modifier.size(MinTouchTarget)) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.cd_refresh))
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {

            ConsoleStatusBar(state = state)

            when {
                state.isLoadingSession -> LoadingState("Opening session…")

                state.error != null && state.events.isEmpty() -> ErrorState(
                    error = state.error!!,
                    onRetry = viewModel::loadSession,
                )

                state.events.isEmpty() -> EmptyState(
                    title = "No output yet",
                    description = if (state.connection == ConnectionState.POLLING) {
                        "This provider reports status only. Task state updates above as it changes."
                    } else {
                        "Waiting for the agent's first response."
                    },
                )

                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (state.droppedLines > 0) {
                        item(key = "dropped") {
                            Text(
                                text = "… ${state.droppedLines} earlier lines trimmed to keep the console responsive",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(state.events, key = { it.id }) { event ->
                        RichChatEvent(event)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConsoleStatusBar(state: ConsoleUiState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TaskStatePill(state.state)
        Text(
            text = when (state.connection) {
                ConnectionState.CONNECTING -> "Connecting…"
                ConnectionState.LIVE -> "Streaming"
                ConnectionState.POLLING -> "Polling status"
                ConnectionState.DISCONNECTED -> "Disconnected"
                ConnectionState.NO_STREAM -> "Finished"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (state.events.isNotEmpty()) {
            Text(
                text = "${state.events.size} lines",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
