package com.amarhelper.console.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amarhelper.console.data.remote.litellm.ModelUsageDto
import com.amarhelper.console.data.remote.litellm.SoftwareUsageDto
import com.amarhelper.console.data.remote.litellm.UsageEventDto
import com.amarhelper.console.data.remote.litellm.UsageSummaryDto
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val LedgerInk = Color(0xFF080B0E)
private val LedgerPanel = Color(0xFF11171B)
private val LedgerLine = Color(0xFF26343B)
private val Acid = Color(0xFFC7F36B)
private val Cyan = Color(0xFF5ED7E8)
private val Violet = Color(0xFFA88BFF)
private val LedgerText = Color(0xFFF2F6EF)
private val LedgerMuted = Color(0xFF8C9A9D)

@Composable
fun ProfileScreen(viewModel: ProfileViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF111A18), LedgerInk, Color(0xFF090B12))),
        ),
    ) {
        when {
            state.loading && state.summary == null -> CircularProgressIndicator(
                color = Acid,
                modifier = Modifier.align(Alignment.Center),
            )
            state.error != null && state.summary == null -> LedgerError(
                message = state.error!!.message,
                onRetry = viewModel::refresh,
                modifier = Modifier.align(Alignment.Center),
            )
            state.summary != null -> LedgerContent(
                summary = state.summary!!,
                refreshing = state.loading,
                onRefresh = viewModel::refresh,
            )
        }
    }
}

@Composable
private fun LedgerContent(summary: UsageSummaryDto, refreshing: Boolean, onRefresh: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { IdentityHeader(summary, refreshing, onRefresh) }
        item { HeroLedger(summary) }
        item { LedgerLabel("SOFTWARE CHANNELS", "attributed model traffic") }
        if (summary.bySoftware.isEmpty()) {
            item { EmptyLedger("No tracked requests yet. Start a task in OpenCode or OpenHands.") }
        } else {
            items(summary.bySoftware, key = { it.software }) { SoftwareCard(it, summary.totals.totalTokens) }
        }
        item { LedgerLabel("MODEL ROUTES", "where tokens were spent") }
        item { ModelRoutes(summary.byModel, summary.totals.totalTokens) }
        item { LedgerLabel("RECENT PULSES", "request metadata only — prompts are never stored") }
        if (summary.recent.isEmpty()) item { EmptyLedger("The ledger is listening.") }
        items(summary.recent.take(12)) { UsagePulse(it) }
        item { PricingFootnote(summary) }
    }
}

@Composable
private fun IdentityHeader(summary: UsageSummaryDto, refreshing: Boolean, onRefresh: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(15.dp)).background(Acid),
            contentAlignment = Alignment.Center,
        ) {
            Text("RK", color = LedgerInk, fontWeight = FontWeight.Black, fontSize = 17.sp)
        }
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text("ROHAN / CONTROL PROFILE", color = LedgerText, fontWeight = FontWeight.Bold)
            Text(
                "${summary.periodDays}D MODEL ECONOMY  •  LIVE",
                color = Acid,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        }
        Surface(onClick = onRefresh, color = LedgerPanel, shape = CircleShape) {
            Icon(
                Icons.Default.Refresh,
                contentDescription = "Refresh ledger",
                tint = if (refreshing) Acid else LedgerMuted,
                modifier = Modifier.padding(12.dp).size(20.dp),
            )
        }
    }
}

@Composable
private fun HeroLedger(summary: UsageSummaryDto) {
    Surface(color = LedgerPanel, shape = RoundedCornerShape(22.dp), tonalElevation = 0.dp) {
        Column(Modifier.padding(20.dp)) {
            Text("ESTIMATED SPEND", color = LedgerMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            Text(
                money(summary.totals.estimatedCostUsd),
                color = LedgerText,
                fontSize = 42.sp,
                lineHeight = 46.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-1.5).sp,
            )
            Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCell("TOKENS", compact(summary.totals.totalTokens), Acid, Modifier.weight(1f))
                StatCell("REQUESTS", summary.totals.requests.toString(), Cyan, Modifier.weight(1f))
                StatCell(
                    "OUTPUT",
                    compact(summary.totals.completionTokens),
                    Violet,
                    Modifier.weight(1f),
                )
            }
            Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Dot(Acid)
                Text(
                    " INPUT ${compact(summary.totals.promptTokens)}",
                    color = LedgerMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.width(14.dp))
                Dot(Violet)
                Text(
                    " OUTPUT ${compact(summary.totals.completionTokens)}",
                    color = LedgerMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFF182126)).padding(12.dp)) {
        Text(value, color = accent, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Text(label, color = LedgerMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun SoftwareCard(usage: SoftwareUsageDto, grandTotal: Long) {
    val accent = if (usage.software == "OpenHands") Cyan else Acid
    val share = if (grandTotal == 0L) 0f else usage.totalTokens.toFloat() / grandTotal
    Surface(color = LedgerPanel, shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Box(Modifier.width(5.dp).height(118.dp).background(accent))
            Column(Modifier.padding(16.dp).weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(usage.software.uppercase(), color = LedgerText, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text(money(usage.estimatedCostUsd), color = accent, fontFamily = FontFamily.Monospace)
                }
                Text(
                    "${compact(usage.totalTokens)} tok  /  ${usage.requests} req  /  ${duration(usage.averageDurationMs)} avg",
                    color = LedgerMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Box(
                    Modifier.padding(top = 15.dp).fillMaxWidth().height(7.dp)
                        .clip(CircleShape).background(LedgerLine),
                ) {
                    Box(
                        Modifier.fillMaxWidth(share.coerceIn(0f, 1f)).height(7.dp)
                            .clip(CircleShape).background(accent),
                    )
                }
                Text("${(share * 100).toInt()}% OF PERIOD LOAD", color = LedgerMuted, fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun ModelRoutes(models: List<ModelUsageDto>, total: Long) {
    Surface(color = LedgerPanel, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (models.isEmpty()) Text("No model traffic", color = LedgerMuted)
            models.forEachIndexed { index, model ->
                val share = if (total == 0L) 0 else model.totalTokens * 100 / total
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("0${index + 1}", color = Acid, fontFamily = FontFamily.Monospace)
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(model.model, color = LedgerText, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${compact(model.totalTokens)} tokens · ${model.requests} calls",
                            color = LedgerMuted,
                            fontSize = 11.sp,
                        )
                    }
                    Text("$share%", color = LedgerText, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
private fun UsagePulse(event: UsageEventDto) {
    Row(
        Modifier.fillMaxWidth().background(Color(0x9911171B), RoundedCornerShape(12.dp)).padding(13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(if (event.statusCode in 200..299) Acid else Color(0xFFFF6B79))
        Column(Modifier.padding(start = 11.dp).weight(1f)) {
            Text(event.software, color = LedgerText, fontWeight = FontWeight.SemiBold)
            Text(
                "${clock(event.createdAt)}  ·  ${event.model}",
                color = LedgerMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(compact(event.totalTokens), color = LedgerText, fontFamily = FontFamily.Monospace)
            Text("${event.durationMs}ms", color = LedgerMuted, fontSize = 9.sp)
        }
    }
}

@Composable
private fun PricingFootnote(summary: UsageSummaryDto) {
    Column(Modifier.padding(vertical = 10.dp)) {
        Text(
            "RATE CARD / 1M TOKENS",
            color = Acid,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
        )
        Text(
            "cache ${money(summary.pricing.inputCacheHitPerMillion)}  ·  input " +
                "${money(summary.pricing.inputCacheMissPerMillion)}  ·  output " +
                money(summary.pricing.outputPerMillion),
            color = LedgerMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
        )
        Text(summary.pricing.note, color = LedgerMuted, fontSize = 10.sp)
    }
}

@Composable
private fun LedgerLabel(title: String, subtitle: String) {
    Column(Modifier.padding(top = 8.dp)) {
        Text(title, color = LedgerText, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
        Text(subtitle, color = LedgerMuted, fontSize = 11.sp)
    }
}

@Composable
private fun EmptyLedger(message: String) {
    Text(
        message,
        color = LedgerMuted,
        modifier = Modifier.fillMaxWidth().background(LedgerPanel, RoundedCornerShape(14.dp)).padding(18.dp),
    )
}

@Composable
private fun LedgerError(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("LEDGER OFFLINE", color = LedgerText, fontWeight = FontWeight.Black)
        Text(message, color = LedgerMuted, modifier = Modifier.padding(vertical = 12.dp))
        Button(onClick = onRetry) { Text("RETRY SYNC") }
    }
}

@Composable
private fun Dot(color: Color) = Box(Modifier.size(7.dp).clip(CircleShape).background(color))

private fun compact(value: Long): String = when {
    value >= 1_000_000 -> String.format(Locale.US, "%.2fM", value / 1_000_000.0)
    value >= 1_000 -> String.format(Locale.US, "%.1fK", value / 1_000.0)
    else -> value.toString()
}

private fun money(value: Double): String = when {
    value == 0.0 -> "$0.00"
    value < 0.01 -> String.format(Locale.US, "$%.6f", value)
    else -> String.format(Locale.US, "$%.2f", value)
}

private fun duration(value: Double): String = if (value >= 1000) {
    String.format(Locale.US, "%.1fs", value / 1000)
} else "${value.toInt()}ms"

private fun clock(epochSeconds: Long): String = DateTimeFormatter.ofPattern("MMM d · HH:mm")
    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(epochSeconds))
