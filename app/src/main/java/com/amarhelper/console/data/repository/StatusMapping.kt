package com.amarhelper.console.data.repository

import com.amarhelper.console.domain.model.TaskState
import java.time.Instant
import java.time.format.DateTimeParseException

/** Provider-specific status vocabularies, normalized onto [TaskState]. */
internal object StatusMapping {

    /** OpenHands `execution_status`, falling back to `sandbox_status`. */
    fun fromOpenHands(executionStatus: String?, sandboxStatus: String?): TaskState {
        executionStatus?.lowercase()?.let { status ->
            when (status) {
                "running" -> return TaskState.RUNNING
                "idle" -> return TaskState.QUEUED
                "paused", "waiting_for_confirmation" -> return TaskState.WAITING
                "finished" -> return TaskState.COMPLETED
                "error", "stuck" -> return TaskState.FAILED
            }
        }
        return when (sandboxStatus?.uppercase()) {
            // "WORKING"/"READY" come from the conversation-start response rather than a
            // sandbox; they are folded in here so a freshly submitted task is never UNKNOWN.
            "WORKING", "STARTING" -> TaskState.QUEUED
            "READY" -> TaskState.RUNNING
            "RUNNING" -> TaskState.RUNNING
            "PAUSED" -> TaskState.WAITING
            "ERROR", "MISSING" -> TaskState.FAILED
            else -> TaskState.UNKNOWN
        }
    }

    /** ISO-8601 timestamps as returned by OpenHands. */
    fun parseIsoTimestamp(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return try {
            Instant.parse(value).toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /**
     * OpenCode reports epoch values; older builds emit seconds rather than milliseconds.
     * Anything below this threshold is treated as seconds.
     */
    fun normalizeEpoch(value: Long?): Long? = when {
        value == null -> null
        value < 100_000_000_000L -> value * 1_000L
        else -> value
    }
}
