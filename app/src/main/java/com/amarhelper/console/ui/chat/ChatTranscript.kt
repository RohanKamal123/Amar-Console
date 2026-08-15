package com.amarhelper.console.ui.chat

import com.amarhelper.console.domain.model.ConsoleEvent

/** What the console actually draws: one entry per turn or tool invocation. */
sealed interface ChatEntry {

    val id: String
    val timestampEpochMillis: Long?

    data class UserMessage(
        override val id: String,
        val blocks: List<ChatBlock>,
        override val timestampEpochMillis: Long?,
    ) : ChatEntry

    data class AgentMessage(
        override val id: String,
        val blocks: List<ChatBlock>,
        override val timestampEpochMillis: Long?,
    ) : ChatEntry

    /**
     * A command and its result as one unit. [output] is null while the action is still
     * running — the card renders a pending state rather than an empty result.
     */
    data class ToolCall(
        override val id: String,
        val name: String,
        val command: String?,
        val output: List<ChatBlock>?,
        val failed: Boolean,
        override val timestampEpochMillis: Long?,
    ) : ChatEntry

    data class SystemNote(
        override val id: String,
        val text: String,
        override val timestampEpochMillis: Long?,
    ) : ChatEntry
}

/**
 * Turns a flat event stream into chat entries.
 *
 * Two things matter here. Actions and their observations are paired through the
 * provider's `cause` linkage so a command and its output form one collapsible card
 * rather than two unrelated lines. And events that are pure bookkeeping — agent state
 * changes — are dropped from the transcript entirely; they belong in a status indicator,
 * not in the conversation.
 */
object ChatTranscript {

    fun build(events: List<ConsoleEvent>): List<ChatEntry> {
        val entries = mutableListOf<ChatEntry>()
        // Observations arrive after their action; index them so the action can absorb them.
        val observationsByCause = events
            .filter { it.causeId != null && !it.isStatusOnly }
            .groupBy { it.causeId!! }
        val consumed = mutableSetOf<String>()

        for (event in events) {
            if (event.isStatusOnly) continue
            if (event.id in consumed) continue

            when (event.kind) {
                ConsoleEvent.Kind.USER -> entries += ChatEntry.UserMessage(
                    id = event.id,
                    blocks = MarkdownParser.parse(event.text),
                    timestampEpochMillis = event.timestampEpochMillis,
                )

                ConsoleEvent.Kind.TOOL -> {
                    val observations = event.eventId?.let { observationsByCause[it] }.orEmpty()
                    observations.forEach { consumed += it.id }
                    val outputText = observations.joinToString("\n") { it.text }.takeIf { it.isNotBlank() }
                    entries += ChatEntry.ToolCall(
                        id = event.id,
                        name = event.toolName ?: "tool",
                        command = event.command?.takeIf { it.isNotBlank() } ?: event.text.takeIf { it.isNotBlank() },
                        output = outputText?.let { MarkdownParser.parse(it) },
                        failed = observations.any { it.kind == ConsoleEvent.Kind.ERROR },
                        timestampEpochMillis = event.timestampEpochMillis,
                    )
                }

                ConsoleEvent.Kind.ERROR -> entries += ChatEntry.ToolCall(
                    id = event.id,
                    name = event.toolName ?: "error",
                    command = event.command,
                    output = MarkdownParser.parse(event.text),
                    failed = true,
                    timestampEpochMillis = event.timestampEpochMillis,
                )

                ConsoleEvent.Kind.SYSTEM -> entries += ChatEntry.SystemNote(
                    id = event.id,
                    text = event.text,
                    timestampEpochMillis = event.timestampEpochMillis,
                )

                ConsoleEvent.Kind.AGENT -> entries += ChatEntry.AgentMessage(
                    id = event.id,
                    blocks = MarkdownParser.parse(event.text),
                    timestampEpochMillis = event.timestampEpochMillis,
                )
            }
        }
        return entries
    }

    /** The latest agent state reported by bookkeeping events, for the status indicator. */
    fun latestAgentState(events: List<ConsoleEvent>): String? =
        events.lastOrNull { it.isStatusOnly && it.agentState != null }?.agentState
}
