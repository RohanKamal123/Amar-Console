package com.amarhelper.console.data.repository

import com.amarhelper.console.domain.model.TaskState
import java.time.Instant
import java.time.format.DateTimeParseException

/** Provider-specific status vocabularies, normalized onto [TaskState]. */
internal object StatusMapping {

    /**
     * Self-hosted OpenHands reports two dimensions: `status` (the conversation) and
     * `runtime_status` (the sandbox behind it). The runtime is checked first because a
     * conversation whose runtime failed still reports `status: RUNNING`.
     *
     * `STOPPED` is mapped to [TaskState.STOPPED] rather than COMPLETED: the server says
     * only that the conversation is not running, and claiming the task succeeded would
     * be an invention.
     */
    fun fromOpenHands(status: String?, runtimeStatus: String?): TaskState {
        val runtime = runtimeStatus?.uppercase()
        if (runtime != null && runtime.contains("ERROR")) return TaskState.FAILED

        return when (status?.uppercase()) {
            "STARTING" -> TaskState.QUEUED
            "RUNNING" -> if (runtime != null && runtime in STARTING_RUNTIME_STATUSES) {
                TaskState.QUEUED
            } else {
                TaskState.RUNNING
            }
            "STOPPED" -> TaskState.STOPPED
            "ARCHIVED" -> TaskState.CANCELLED
            else -> TaskState.UNKNOWN
        }
    }

    /** Runtime states that mean "not ready to work yet". */
    private val STARTING_RUNTIME_STATUSES = setOf(
        "STATUS\$BUILDING_RUNTIME",
        "STATUS\$STARTING_RUNTIME",
        "STATUS\$SETTING_UP_WORKSPACE",
        "STATUS\$SETTING_UP_GIT_HOOKS",
    )

    /** ISO-8601 timestamps as returned by OpenHands. */
    fun parseIsoTimestamp(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return try {
            Instant.parse(value).toEpochMilli()
        } catch (_: DateTimeParseException) {
            // The server emits naive datetimes for some fields; retry as local time.
            try {
                java.time.LocalDateTime.parse(value)
                    .atZone(java.time.ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            } catch (_: DateTimeParseException) {
                null
            }
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
