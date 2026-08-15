package com.amarhelper.console.ui.chat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amarhelper.console.ui.theme.ConsoleTextStyle

/** Renders parsed markdown blocks. */
@Composable
fun MarkdownView(
    blocks: List<ChatBlock>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block -> BlockView(block) }
    }
}

@Composable
private fun BlockView(block: ChatBlock) {
    when (block) {
        is ChatBlock.Paragraph -> Text(
            text = block.spans.toAnnotated(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        is ChatBlock.Heading -> Text(
            text = block.spans.toAnnotated(),
            style = when (block.level) {
                1 -> MaterialTheme.typography.titleLarge
                2 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.labelLarge
            },
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 4.dp),
        )

        is ChatBlock.BulletList -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            block.items.forEach { item -> MarkerRow("•", item) }
        }

        is ChatBlock.NumberedList -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            block.items.forEachIndexed { index, item -> MarkerRow("${index + 1}.", item) }
        }

        is ChatBlock.Code -> CodeBlockView(block)

        is ChatBlock.Diff -> DiffView(block)

        is ChatBlock.Quote -> Row {
            HorizontalDivider(
                modifier = Modifier
                    .width(3.dp)
                    .padding(end = 10.dp),
                color = MaterialTheme.colorScheme.outline,
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                block.blocks.forEach { BlockView(it) }
            }
        }

        ChatBlock.Divider -> HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun MarkerRow(marker: String, item: ListItem) {
    Row(modifier = Modifier.padding(start = (item.depth * 14).dp)) {
        Text(
            text = marker,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(22.dp),
        )
        Text(
            text = item.spans.toAnnotated(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun List<Span>.toAnnotated(): AnnotatedString {
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val linkColor = MaterialTheme.colorScheme.primary
    return buildAnnotatedString {
        this@toAnnotated.forEach { span ->
            val style = SpanStyle(
                fontWeight = if (span.bold) FontWeight.Bold else null,
                fontStyle = if (span.italic) FontStyle.Italic else null,
                fontFamily = if (span.code) FontFamily.Monospace else null,
                background = if (span.code) codeBackground else androidx.compose.ui.graphics.Color.Unspecified,
                color = if (span.link != null) linkColor else androidx.compose.ui.graphics.Color.Unspecified,
                textDecoration = if (span.link != null) TextDecoration.Underline else null,
                fontSize = if (span.code) 14.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
            )
            withStyle(style) { append(span.text) }
        }
    }
}

/** Long lines scroll horizontally rather than wrapping mid-token, which ruins code. */
@Composable
internal fun HorizontallyScrollable(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
    ) { content() }
}

internal val CodeStyle = ConsoleTextStyle
