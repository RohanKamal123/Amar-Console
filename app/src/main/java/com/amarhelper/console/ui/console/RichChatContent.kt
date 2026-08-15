package com.amarhelper.console.ui.console

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.amarhelper.console.domain.model.ConsoleEvent
import com.amarhelper.console.domain.model.ToolCall
import com.amarhelper.console.ui.components.clockTime
import com.amarhelper.console.ui.theme.Rose400

@Composable
internal fun RichChatEvent(event: ConsoleEvent) {
    when (event.kind) {
        ConsoleEvent.Kind.TOOL -> ToolCard(event)
        ConsoleEvent.Kind.SYSTEM, ConsoleEvent.Kind.ERROR -> StatusMessage(event)
        ConsoleEvent.Kind.USER, ConsoleEvent.Kind.AGENT -> ChatMessage(event)
    }
}

@Composable
private fun ChatMessage(event: ConsoleEvent) {
    val user = event.kind == ConsoleEvent.Kind.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (user) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (user) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (user) 18.dp else 4.dp,
                bottomEnd = if (user) 4.dp else 18.dp,
            ),
            modifier = Modifier.fillMaxWidth(if (user) 0.88f else 0.96f),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (user) "You" else "Agent",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    event.timestampEpochMillis?.let {
                        Text(clockTime(it), style = MaterialTheme.typography.labelSmall)
                    }
                }
                MarkdownContent(event.text)
            }
        }
    }
}

@Composable
private fun StatusMessage(event: ConsoleEvent) {
    val error = event.kind == ConsoleEvent.Kind.ERROR
    Surface(
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = event.text,
            style = MaterialTheme.typography.bodySmall,
            color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun ToolCard(event: ConsoleEvent) {
    val tool = event.tool ?: ToolCall(name = event.text.substringBefore('('), output = event.text)
    var expanded by rememberSaveable(event.id) { mutableStateOf(false) }
    Surface(
        tonalElevation = 1.dp,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Icon(Icons.Filled.Terminal, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(modifier = Modifier.weight(1f)) {
                    Text(tool.name.replace('_', ' '), style = MaterialTheme.typography.labelLarge)
                    tool.command?.lineSequence()?.firstOrNull()?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                }
                tool.succeeded?.let {
                    Text(if (it) "Done" else "Failed", color = if (it) MaterialTheme.colorScheme.primary else Rose400)
                }
                Icon(
                    Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse tool output" else "Expand tool output",
                    modifier = Modifier.rotate(if (expanded) 180f else 0f),
                )
            }
            AnimatedVisibility(expanded) {
                Column(
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    tool.command?.let { CodeBlock("shell", it) }
                    tool.output?.takeIf { it.isNotBlank() && it != tool.command }?.let {
                        if (tool.isDiff || RichTextParser.looksLikeDiff(it)) DiffBlock(it) else CodeBlock(tool.language, it)
                    }
                }
            }
        }
    }
}

@Composable
internal fun MarkdownContent(source: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RichTextParser.parse(source).forEach { block ->
            when (block) {
                is RichBlock.Heading -> Text(
                    inlineMarkdown(block.text),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        else -> MaterialTheme.typography.titleMedium
                    },
                    fontWeight = FontWeight.Bold,
                )
                is RichBlock.Paragraph -> Text(inlineMarkdown(block.text), style = MaterialTheme.typography.bodyMedium)
                is RichBlock.ListItem -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (block.ordered) "${block.number}." else "•", fontWeight = FontWeight.Bold)
                    Text(inlineMarkdown(block.text), style = MaterialTheme.typography.bodyMedium)
                }
                is RichBlock.Code -> CodeBlock(block.language, block.text)
                is RichBlock.Diff -> DiffBlock(block.text)
            }
        }
    }
}

@Composable
private fun CodeBlock(language: String?, code: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
            .padding(10.dp),
    ) {
        language?.takeIf { it.isNotBlank() }?.let {
            Text(it.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        Text(
            highlightedCode(code, language, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        )
    }
}

@Composable
private fun DiffBlock(diff: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
            .padding(vertical = 8.dp),
    ) {
        diff.lines().forEach { line ->
            val (background, foreground) = when {
                line.startsWith("+") && !line.startsWith("+++") -> Color(0x3322C55E) to Color(0xFF15803D)
                line.startsWith("-") && !line.startsWith("---") -> Color(0x33EF4444) to Color(0xFFB91C1C)
                line.startsWith("@@") -> Color(0x332563EB) to MaterialTheme.colorScheme.primary
                else -> Color.Transparent to MaterialTheme.colorScheme.onSurface
            }
            Text(
                line.ifEmpty { " " },
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = foreground,
                modifier = Modifier.fillMaxWidth().background(background).padding(horizontal = 10.dp, vertical = 1.dp),
            )
        }
    }
}

private fun inlineMarkdown(source: String): AnnotatedString = buildAnnotatedString {
    var index = 0
    while (index < source.length) {
        val marker = when {
            source.startsWith("**", index) -> "**"
            source[index] == '`' -> "`"
            source[index] == '*' || source[index] == '_' -> source[index].toString()
            else -> null
        }
        if (marker == null) {
            append(source[index++])
            continue
        }
        val end = source.indexOf(marker, index + marker.length)
        if (end < 0) {
            append(marker)
            index += marker.length
            continue
        }
        val start = length
        append(source.substring(index + marker.length, end))
        addStyle(
            when (marker) {
                "**" -> SpanStyle(fontWeight = FontWeight.Bold)
                "`" -> SpanStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                else -> SpanStyle(fontStyle = FontStyle.Italic)
            },
            start,
            length,
        )
        index = end + marker.length
    }
}

private fun highlightedCode(code: String, language: String?, keyword: Color, literal: Color): AnnotatedString =
    buildAnnotatedString {
        append(code)
        val keywords = when (language?.lowercase()) {
            "kotlin", "kt" -> "fun|val|var|class|object|interface|when|if|else|return|suspend|data|sealed|import|package"
            "python", "py" -> "def|class|if|else|elif|return|import|from|for|while|async|await|with|yield"
            "javascript", "typescript", "js", "ts" -> "const|let|var|function|class|if|else|return|import|export|async|await|interface|type"
            "json" -> "true|false|null"
            "shell", "bash", "sh" -> "if|then|else|fi|for|do|done|case|esac|function"
            else -> null
        }
        keywords?.let { pattern ->
            Regex("\\b($pattern)\\b").findAll(code).forEach { addStyle(SpanStyle(color = keyword, fontWeight = FontWeight.SemiBold), it.range.first, it.range.last + 1) }
        }
        Regex("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'").findAll(code).forEach {
            addStyle(SpanStyle(color = literal), it.range.first, it.range.last + 1)
        }
    }
