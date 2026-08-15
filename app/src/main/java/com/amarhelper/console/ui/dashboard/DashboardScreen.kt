package com.amarhelper.console.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AddTask
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amarhelper.console.R
import com.amarhelper.console.domain.model.AgentProvider
import com.amarhelper.console.domain.model.AgentSession
import com.amarhelper.console.domain.model.ServiceHealth
import com.amarhelper.console.ui.components.EmptyState
import com.amarhelper.console.ui.components.HealthPill
import com.amarhelper.console.ui.components.LoadingState
import com.amarhelper.console.ui.components.MinTouchTarget
import com.amarhelper.console.ui.components.TaskStatePill
import com.amarhelper.console.ui.components.formatLatency
import com.amarhelper.console.ui.components.relativeTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onNewTask: () -> Unit,
    onOpenSessions: () -> Unit,
    onOpenServices: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSession: (AgentProvider, String) -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Console", style = MaterialTheme.typography.titleLarge) },
                actions = {
                    IconButton(
                        onClick = { viewModel.refresh() },
                        modifier = Modifier.size(MinTouchTarget),
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.cd_refresh))
                    }
                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.size(MinTouchTarget),
                    ) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.cd_settings))
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            if (state.isRefreshing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            when {
                state.isLoading -> LoadingState("Checking your infrastructure…")

                !state.hasConfiguration -> EmptyState(
                    title = "No services configured",
                    description = "Add the URL of your OpenHands, OpenCode or gateway endpoint to get started.",
                    action = {
                        androidx.compose.material3.Button(
                            onClick = onOpenSettings,
                            modifier = Modifier.heightIn(min = MinTouchTarget),
                        ) { Text("Open settings") }
                    },
                )

                else -> LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        SummaryLine(
                            online = state.onlineServices,
                            configured = state.configuredServices,
                            active = state.activeSessions.size,
                        )
                    }

                    item { SectionLabel("Quick actions") }
                    item {
                        QuickActionRow(
                            icon = Icons.Filled.AddTask,
                            title = "New agent task",
                            subtitle = "Describe a task and hand it to an agent",
                            onClick = onNewTask,
                        )
                    }
                    item {
                        QuickActionRow(
                            icon = Icons.AutoMirrored.Filled.List,
                            title = "Sessions",
                            subtitle = "Open or resume a conversation",
                            onClick = onOpenSessions,
                        )
                    }
                    item {
                        QuickActionRow(
                            icon = Icons.Filled.Dns,
                            title = "Services",
                            subtitle = "Health, latency and versions",
                            onClick = onOpenServices,
                        )
                    }

                    item { SectionLabel("Services") }
                    items(state.services, key = { it.service.name }) { health ->
                        ServiceLine(health = health, onClick = onOpenServices)
                    }

                    item { SectionLabel("Recent activity") }
                    when {
                        state.sessionsError != null && state.recentSessions.isEmpty() -> item {
                            Text(
                                text = state.sessionsError!!.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }

                        state.recentSessions.isEmpty() -> item {
                            EmptyState(
                                title = "No sessions yet",
                                description = "Tasks you start will appear here.",
                            )
                        }

                        else -> items(state.recentSessions, key = { it.provider.name + it.id }) { session ->
                            SessionLine(session = session, onClick = { onOpenSession(session.provider, session.id) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryLine(online: Int, configured: Int, active: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        Metric(value = "$online/$configured", label = "services online")
        Metric(value = active.toString(), label = "active sessions")
    }
}

@Composable
private fun Metric(value: String, label: String) {
    Column {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 20.dp, bottom = 2.dp),
    )
}

@Composable
private fun QuickActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ServiceLine(health: ServiceHealth, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = health.service.displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatLatency(health.latencyMillis),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 10.dp),
            )
            HealthPill(health.state)
        }
        HorizontalDivider(
            modifier = Modifier.padding(top = 10.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

@Composable
private fun SessionLine(session: AgentSession, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        Text(
            text = session.title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Box(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TaskStatePill(session.state)
            Text(
                text = "${session.provider.displayName} · ${relativeTime(session.lastActivityEpochMillis)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(
            modifier = Modifier.padding(top = 10.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}
