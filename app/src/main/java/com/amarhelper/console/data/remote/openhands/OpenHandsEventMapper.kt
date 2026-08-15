package com.amarhelper.console.data.remote.openhands

import com.amarhelper.console.domain.model.ConsoleEvent
import com.amarhelper.console.domain.model.TaskState
import com.amarhelper.console.domain.model.ToolCall
import com.amarhelper.console.data.repository.StatusMapping
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal object OpenHandsEventMapper {
    sealed interface Result {
        data class Transcript(val event: ConsoleEvent) : Result
        data class Status(val label: String, val state: TaskState?) : Result
        data object Ignore : Result
    }

    fun map(value: JsonObject): Result {
        fun str(key: String): String? =
            (value[key] as? JsonPrimitive)?.takeIf { it.isString || key in PRIMITIVE_KEYS }?.content
        fun obj(key: String): JsonObject? = value[key] as? JsonObject
        fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.content

        val source = str("source")
        val action = str("action")
        val observation = str("observation")
        if (observation in STATE_OBSERVATIONS) {
            val state = obj("extras")?.string("agent_state").orEmpty()
            return Result.Status(statusLabel(state), state.toTaskState())
        }
        if (action in INTERNAL_ACTIONS || observation in INTERNAL_OBSERVATIONS) return Result.Ignore

        val message = str("message")?.takeIf { it.isNotBlank() }
        val args = obj("args")
        val extras = obj("extras")
        val content = str("content")?.takeIf { it.isNotBlank() }
        val command = args?.string("command") ?: extras?.string("command")
        val diff = str("diff") ?: extras?.string("diff")
        val isTool = action in TOOL_ACTIONS || observation in TOOL_OBSERVATIONS
        val toolName = action ?: observation
        val tool = if (isTool && toolName != null) {
            ToolCall(
                name = toolName,
                callId = str("id"),
                causeId = str("cause"),
                command = command,
                output = diff ?: content,
                language = command?.let { "shell" },
                isDiff = diff != null || content?.let(::looksLikeUnifiedDiff) == true,
                succeeded = str("success")?.toBooleanStrictOrNull(),
            )
        } else null
        val text = message ?: command ?: diff ?: content
            ?: action?.let { "$it()" }
            ?: observation?.let { "$it observed" }
            ?: return Result.Ignore
        val kind = when {
            observation == "error" -> ConsoleEvent.Kind.ERROR
            source == "user" -> ConsoleEvent.Kind.USER
            isTool -> ConsoleEvent.Kind.TOOL
            source == "environment" -> ConsoleEvent.Kind.SYSTEM
            else -> ConsoleEvent.Kind.AGENT
        }
        return Result.Transcript(
            ConsoleEvent(
                id = str("id") ?: text.hashCode().toString(),
                kind = kind,
                text = text,
                timestampEpochMillis = StatusMapping.parseIsoTimestamp(str("timestamp")),
                tool = tool,
            ),
        )
    }

    fun transcript(events: List<JsonObject>): List<ConsoleEvent> = mergeToolEvents(
        events.mapNotNull { (map(it) as? Result.Transcript)?.event },
    )

    private fun mergeToolEvents(events: List<ConsoleEvent>): List<ConsoleEvent> {
        val merged = mutableListOf<ConsoleEvent>()
        val actionIndex = mutableMapOf<String, Int>()
        events.forEach { event ->
            val tool = event.tool
            val actionPosition = tool?.causeId?.let(actionIndex::get)
            if (tool != null && actionPosition != null) {
                val action = merged[actionPosition]
                val actionTool = action.tool!!
                merged[actionPosition] = action.copy(
                    tool = actionTool.copy(
                        output = tool.output ?: actionTool.output,
                        isDiff = tool.isDiff || actionTool.isDiff,
                        succeeded = tool.succeeded ?: actionTool.succeeded,
                    ),
                )
            } else {
                merged += event
                tool?.callId?.let { actionIndex[it] = merged.lastIndex }
            }
        }
        return merged
    }

    private fun String.toTaskState(): TaskState? = when (lowercase()) {
        "running", "thinking" -> TaskState.RUNNING
        "awaiting_user_input", "awaiting_user_confirmation", "paused" -> TaskState.WAITING
        "finished" -> TaskState.COMPLETED
        "stopped" -> TaskState.STOPPED
        "error" -> TaskState.FAILED
        else -> null
    }

    private fun statusLabel(state: String): String = when (state.lowercase()) {
        "running" -> "Agent: running"
        "thinking" -> "Agent: thinking…"
        "awaiting_user_input", "awaiting_user_confirmation" -> "Agent: waiting"
        "paused" -> "Agent: paused"
        "finished" -> "Agent: finished"
        "stopped" -> "Agent: stopped"
        "error" -> "Agent: error"
        "loading" -> "Agent: loading…"
        else -> "Agent: ${state.ifBlank { "updating" }}"
    }

    private fun looksLikeUnifiedDiff(text: String): Boolean =
        text.lineSequence().any { it.startsWith("@@ ") } &&
            text.lineSequence().any { it.startsWith("--- ") || it.startsWith("+++ ") }

    private val PRIMITIVE_KEYS = setOf("id", "cause", "success")
    private val STATE_OBSERVATIONS = setOf("agent_state_changed", "agent_state_change_observed")
    private val INTERNAL_ACTIONS = setOf("system", "change_agent_state", "recall", "think", "condensation", "condensation_request", "null")
    private val INTERNAL_OBSERVATIONS = setOf("recall", "think", "null", "condense", "loop_detection")
    private val TOOL_ACTIONS = setOf("read", "write", "edit", "run", "run_ipython", "browse", "browse_interactive", "call_tool_mcp", "delegate", "push", "send_pr", "task_tracking")
    private val TOOL_OBSERVATIONS = setOf("read", "write", "edit", "run", "run_ipython", "browse", "delegate", "mcp", "download", "task_tracking")
}
