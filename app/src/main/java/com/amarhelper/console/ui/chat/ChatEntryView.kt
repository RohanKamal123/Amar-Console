package com.amarhelper.console.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.amarhelper.console.ui.components.clockTime
import com.amarhelper.console.ui.theme.Rose400

/**
 * One transcript entry.
 *
 * User and agent turns are separated by alignment and surface rather than by a label, so
 * the conversation reads like a conversation. Tool calls are cards, collapsed by default:
 * an agent run produces far more tool output than prose, and expanding on demand keeps
 * the thread readable.
 */
@Composable
fun ChatEntryView(entry: ChatEntry, modifier: Modifier = Modifier) {
    when (entry) {
        is ChatEntry.UserMessage -> UserBubble(entry, modifier)
        is ChatEntry.AgentMessage -> AgentTurn(entry, modifier)
        is ChatEntry.ToolCall -> ToolCallCard(entry, modifier)
        is ChatEntry.SystemNote -> SystemNote(entry, modifier)
    }
}

@Composable
private fun UserBubble(entry: ChatEntry.UserMessage, modifier: Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            MarkdownView(entry.blocks)
            Timestamp(entry.timestampEpochMillis, Modifier.align(Alignment.End))
        }
    }
}

@Composable
private fun AgentTurn(entry: ChatEntry.AgentMessage, modifier: Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        MarkdownView(entry.blocks)
        Timestamp(entry.timestampEpochMillis)
    }
}

@Composable
private fun ToolCallCard(entry: ChatEntry.ToolCall, modifier: Modifier) {
    var expanded by rememberSaveable(entry.id) { mutableStateOf(false) }
    val border = if (entry.failed) Rose400 else MaterialTheme.colorScheme.outlineVariant

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .clickable { expanded = !expanded }
            .padding(12.dp)
            .semantics {
                contentDescription = buildString {
                    append(if (entry.failed) "Failed tool call " else "Tool call ")
                    append(entry.name)
                    append(if (expanded) ", expanded" else ", collapsed. Double tap to expand")
                }
            },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                imageVector = when {
                    entry.failed -> Icons.Filled.ErrorOutline
                    entry.output == null -> Icons.Filled.Terminal
                    else -> Icons.Filled.CheckCircle
                },
                contentDescription = null,
                tint = if (entry.failed) Rose400 else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = entry.name,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = entry.command.orEmpty(),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (entry.output == null) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
            } else {
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                entry.command?.let { command ->
                    CodeBlockView(
                        ChatBlock.Code(
                            language = null,
                            lines = command.lines().map { SyntaxHighlighter.tokenize(it, "bash") },
                            raw = command,
                        ),
                    )
                }
                entry.output?.let { MarkdownView(it) }
                    ?: Text(
                        text = "Waiting for output…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
            }
        }
    }
}

@Composable
private fun SystemNote(entry: ChatEntry.SystemNote, modifier: Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 20.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = entry.text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Timestamp(epochMillis: Long?, modifier: Modifier = Modifier) {
    if (epochMillis == null) return
    Text(
        text = clockTime(epochMillis),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = 4.dp),
    )
}
