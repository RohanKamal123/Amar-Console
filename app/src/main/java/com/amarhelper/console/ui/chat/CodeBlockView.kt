package com.amarhelper.console.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.dp
import com.amarhelper.console.ui.theme.Amber400
import com.amarhelper.console.ui.theme.Azure300
import com.amarhelper.console.ui.theme.Emerald400
import com.amarhelper.console.ui.theme.Mist300
import com.amarhelper.console.ui.theme.Rose400

@Composable
fun CodeBlockView(block: ChatBlock.Code, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(vertical = 10.dp),
    ) {
        block.language?.let { language ->
            Text(
                text = language,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, bottom = 6.dp),
            )
        }
        HorizontallyScrollable {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                block.lines.forEach { tokens ->
                    Text(text = tokens.toAnnotated(), style = CodeStyle)
                }
            }
        }
    }
}

@Composable
private fun List<Token>.toAnnotated() = buildAnnotatedString {
    val plain = MaterialTheme.colorScheme.onSurface
    forEach { token ->
        withStyle(SpanStyle(color = token.type.color(plain))) { append(token.text) }
    }
}

private fun Token.Type.color(plain: Color): Color = when (this) {
    Token.Type.Keyword -> Azure300
    Token.Type.StringLiteral -> Emerald400
    Token.Type.Number -> Amber400
    Token.Type.Comment -> Mist300
    Token.Type.Function -> Azure300
    Token.Type.Punctuation -> Rose400
    Token.Type.Plain -> plain
}

@Composable
fun DiffView(block: ChatBlock.Diff, modifier: Modifier = Modifier) {
    val (added, removed) = DiffParser.summarize(block.lines)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("diff", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("+$added", style = MaterialTheme.typography.labelMedium, color = Emerald400)
            Text("−$removed", style = MaterialTheme.typography.labelMedium, color = Rose400)
        }
        HorizontallyScrollable {
            Column(modifier = Modifier.padding(horizontal = 4.dp)) {
                block.lines.forEach { line -> DiffLineView(line) }
            }
        }
    }
}

@Composable
private fun DiffLineView(line: DiffLine) {
    val (background, color, prefix) = when (line.kind) {
        DiffLine.Kind.Added -> Triple(Emerald400.copy(alpha = 0.12f), Emerald400, "+")
        DiffLine.Kind.Removed -> Triple(Rose400.copy(alpha = 0.12f), Rose400, "−")
        DiffLine.Kind.HunkHeader -> Triple(Azure300.copy(alpha = 0.10f), Azure300, "")
        DiffLine.Kind.FileHeader -> Triple(Color.Transparent, MaterialTheme.colorScheme.onSurfaceVariant, "")
        DiffLine.Kind.Context -> Triple(Color.Transparent, MaterialTheme.colorScheme.onSurface, " ")
    }
    Text(
        text = prefix + line.text,
        style = CodeStyle,
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .padding(horizontal = 8.dp, vertical = 1.dp),
    )
}
