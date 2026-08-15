package com.amarhelper.console.ui.services

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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amarhelper.console.R
import com.amarhelper.console.domain.model.DependencyHealth
import com.amarhelper.console.domain.model.ServiceHealth
import com.amarhelper.console.ui.components.HealthPill
import com.amarhelper.console.ui.components.LoadingState
import com.amarhelper.console.ui.components.MinTouchTarget
import com.amarhelper.console.ui.components.formatLatency
import com.amarhelper.console.ui.components.relativeTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServicesScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: ServicesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Services") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(MinTouchTarget)) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh, modifier = Modifier.size(MinTouchTarget)) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.cd_refresh))
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (state.isLoading && state.services.isEmpty()) {
                LoadingState("Probing services…")
                return@Column
            }

            LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)) {
                items(state.services, key = { it.service.name }) { health ->
                    ServiceCard(health = health, onConfigure = onOpenSettings)
                }

                item {
                    Text(
                        text = "DATA STORES",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
                    )
                }

                if (state.dependencies.isEmpty()) {
                    item {
                        Text(
                            text = if (state.gatewayConfigured) {
                                "Your gateway's /health response contains no dependency section, " +
                                    "so PostgreSQL and Redis state is unknown."
                            } else {
                                "PostgreSQL and Redis are never contacted from the phone. " +
                                    "Configure a gateway that reports them in /health to see their state here."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(state.dependencies, key = { it.name }) { dependency ->
                        DependencyRow(dependency)
                    }
                }
            }
        }
    }
}

@Composable
private fun ServiceCard(health: ServiceHealth, onConfigure: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = health.service.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            HealthPill(health.state)
        }

        Row(
            modifier = Modifier.padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Detail("Latency", formatLatency(health.latencyMillis))
            Detail("Checked", relativeTime(health.lastCheckedEpochMillis))
            Detail("Version", health.version ?: "—")
        }

        health.detail?.let { detail ->
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        if (health.state == com.amarhelper.console.domain.model.HealthState.NOT_CONFIGURED) {
            Button(
                onClick = onConfigure,
                modifier = Modifier.padding(top = 10.dp).heightIn(min = MinTouchTarget),
            ) { Text("Configure") }
        }

        HorizontalDivider(
            modifier = Modifier.padding(top = 14.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun DependencyRow(dependency: DependencyHealth) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = dependency.name,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        HealthPill(dependency.state)
    }
}
