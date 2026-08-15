package com.amarhelper.console.domain.model

/** Which agent backend a session belongs to. */
enum class AgentProvider(val displayName: String) {
    OPEN_HANDS("OpenHands"),
    OPEN_CODE("OpenCode"),
}

/**
 * Lifecycle of an agent task, normalized across providers.
 *
 * OpenHands reports `execution_status` (idle/running/paused/waiting_for_confirmation/
 * finished/error/stuck) plus a sandbox status; OpenCode reports message completion.
 * Both are mapped onto this single vocabulary so the UI has one state machine.
 */
enum class TaskState {
    QUEUED,
    RUNNING,
    WAITING,
    COMPLETED,
    /** Not running, with no claim about whether the work finished — OpenHands' `STOPPED`. */
    STOPPED,
    FAILED,
    CANCELLED,
    UNKNOWN;

    val isTerminal: Boolean
        get() = this == COMPLETED || this == FAILED || this == CANCELLED || this == STOPPED

    val isActive: Boolean
        get() = this == QUEUED || this == RUNNING || this == WAITING
}

/** A conversation with an agent. */
data class AgentSession(
    val id: String,
    val provider: AgentProvider,
    val title: String,
    val state: TaskState,
    val createdAtEpochMillis: Long?,
    val lastActivityEpochMillis: Long?,
    val repository: String? = null,
    val detail: String? = null,
)

/** One line in the session console. */
data class ConsoleEvent(
    val id: String,
    val kind: Kind,
    val text: String,
    val timestampEpochMillis: Long?,
    val tool: ToolCall? = null,
) {
    enum class Kind { USER, AGENT, TOOL, SYSTEM, ERROR }
}

/** Structured action data retained from provider events for rich, expandable rendering. */
data class ToolCall(
    val name: String,
    val callId: String? = null,
    val causeId: String? = null,
    val command: String? = null,
    val output: String? = null,
    val language: String? = null,
    val isDiff: Boolean = false,
    val succeeded: Boolean? = null,
)

sealed interface RealtimeUpdate {
    data object Connected : RealtimeUpdate
    data object Reconnecting : RealtimeUpdate
    data class Event(val event: ConsoleEvent) : RealtimeUpdate
    data class AgentStatus(val label: String, val state: TaskState? = null) : RealtimeUpdate
}

/** A task the user is composing or has just submitted. */
data class TaskSubmission(
    val prompt: String,
    val provider: AgentProvider,
    val repository: String? = null,
)
