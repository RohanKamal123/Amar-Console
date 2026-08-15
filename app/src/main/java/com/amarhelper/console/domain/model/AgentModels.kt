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

/**
 * One event from an agent.
 *
 * [eventId] and [causeId] carry the provider's own linkage: an observation names the
 * action it answers, which is what lets the transcript pair a command with its output
 * into a single card instead of two loose lines.
 */
data class ConsoleEvent(
    val id: String,
    val kind: Kind,
    val text: String,
    val timestampEpochMillis: Long?,
    val eventId: Long? = null,
    val causeId: Long? = null,
    /** The tool or action name, when this event is one. */
    val toolName: String? = null,
    /** The command or arguments an action was invoked with. */
    val command: String? = null,
    /** True for provider bookkeeping (agent state changes) rather than conversation content. */
    val isStatusOnly: Boolean = false,
    /** Agent state reported by a status event, e.g. `running`, `awaiting_user_input`. */
    val agentState: String? = null,
) {
    enum class Kind { USER, AGENT, TOOL, SYSTEM, ERROR }
}

/** A task the user is composing or has just submitted. */
data class TaskSubmission(
    val prompt: String,
    val provider: AgentProvider,
    val repository: String? = null,
)
