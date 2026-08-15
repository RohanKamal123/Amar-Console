package com.amarhelper.console.ui.components

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
private val dateFormatter = DateTimeFormatter.ofPattern("d MMM, HH:mm")

/** "just now", "4m ago", "2h ago", then an absolute date. */
fun relativeTime(epochMillis: Long?, now: Long = System.currentTimeMillis()): String {
    if (epochMillis == null || epochMillis <= 0L) return "—"
    val delta = (now - epochMillis).coerceAtLeast(0L)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(delta)
    val hours = TimeUnit.MILLISECONDS.toHours(delta)
    val days = TimeUnit.MILLISECONDS.toDays(delta)
    return when {
        delta < TimeUnit.MINUTES.toMillis(1) -> "just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        days < 7 -> "${days}d ago"
        else -> absoluteDate(epochMillis)
    }
}

fun absoluteDate(epochMillis: Long): String =
    dateFormatter.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

fun clockTime(epochMillis: Long?): String =
    epochMillis?.let { timeFormatter.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) } ?: "--:--:--"

fun formatLatency(millis: Long?): String = when {
    millis == null -> "—"
    millis < 1_000 -> "${millis} ms"
    else -> String.format(Locale.getDefault(), "%.1f s", millis / 1000.0)
}
