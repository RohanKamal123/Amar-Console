package com.amarhelper.console.ui.chat

import com.amarhelper.console.domain.model.ConsoleEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTranscriptTest {

    private fun event(
        id: String,
        kind: ConsoleEvent.Kind,
        text: String,
        eventId: Long? = null,
        causeId: Long? = null,
        toolName: String? = null,
        command: String? = null,
        statusOnly: Boolean = false,
        agentState: String? = null,
    ) = ConsoleEvent(
        id = id,
        kind = kind,
        text = text,
        timestampEpochMillis = 1_700_000_000_000L,
        eventId = eventId,
        causeId = causeId,
        toolName = toolName,
        command = command,
        isStatusOnly = statusOnly,
        agentState = agentState,
    )

    @Test
    fun `a command and its output become one card`() {
        val entries = ChatTranscript.build(
            listOf(
                event("1", ConsoleEvent.Kind.TOOL, "run", eventId = 1, toolName = "run", command = "pytest -q"),
                event("2", ConsoleEvent.Kind.TOOL, "12 passed", causeId = 1),
            ),
        )

        val call = entries.single() as ChatEntry.ToolCall
        assertEquals("run", call.name)
        assertEquals("pytest -q", call.command)
        assertTrue(call.output!!.isNotEmpty())
    }

    @Test
    fun `an action still awaiting its observation renders as pending`() {
        val entries = ChatTranscript.build(
            listOf(event("1", ConsoleEvent.Kind.TOOL, "run", eventId = 1, toolName = "run", command = "sleep 30")),
        )

        assertNull((entries.single() as ChatEntry.ToolCall).output)
    }

    @Test
    fun `a failed observation marks its card failed`() {
        val entries = ChatTranscript.build(
            listOf(
                event("1", ConsoleEvent.Kind.TOOL, "run", eventId = 1, toolName = "run", command = "false"),
                event("2", ConsoleEvent.Kind.ERROR, "exit code 1", causeId = 1),
            ),
        )

        val call = entries.single() as ChatEntry.ToolCall
        assertTrue(call.failed)
    }

    @Test
    fun `agent state changes never reach the transcript`() {
        val entries = ChatTranscript.build(
            listOf(
                event("u", ConsoleEvent.Kind.USER, "do the thing"),
                event("s1", ConsoleEvent.Kind.SYSTEM, "", statusOnly = true, agentState = "running"),
                event("s2", ConsoleEvent.Kind.SYSTEM, "", statusOnly = true, agentState = "awaiting_user_input"),
            ),
        )

        assertEquals(1, entries.size)
        assertTrue(entries.single() is ChatEntry.UserMessage)
    }

    @Test
    fun `the latest agent state is available for the status indicator`() {
        val events = listOf(
            event("s1", ConsoleEvent.Kind.SYSTEM, "", statusOnly = true, agentState = "running"),
            event("s2", ConsoleEvent.Kind.SYSTEM, "", statusOnly = true, agentState = "finished"),
        )

        assertEquals("finished", ChatTranscript.latestAgentState(events))
        assertNull(ChatTranscript.latestAgentState(emptyList()))
    }

    @Test
    fun `user and agent turns keep their order and identity`() {
        val entries = ChatTranscript.build(
            listOf(
                event("1", ConsoleEvent.Kind.USER, "Add auth"),
                event("2", ConsoleEvent.Kind.AGENT, "Working on **auth** now"),
            ),
        )

        assertTrue(entries[0] is ChatEntry.UserMessage)
        assertTrue(entries[1] is ChatEntry.AgentMessage)
    }

    @Test
    fun `agent markdown is parsed into blocks rather than left as text`() {
        val entries = ChatTranscript.build(
            listOf(event("1", ConsoleEvent.Kind.AGENT, "# Title\n\n```py\nx = 1\n```")),
        )

        val blocks = (entries.single() as ChatEntry.AgentMessage).blocks
        assertTrue(blocks.any { it is ChatBlock.Heading })
        assertTrue(blocks.any { it is ChatBlock.Code })
    }

    @Test
    fun `an observation is not also rendered on its own`() {
        val entries = ChatTranscript.build(
            listOf(
                event("1", ConsoleEvent.Kind.TOOL, "edit", eventId = 1, toolName = "edit"),
                event("2", ConsoleEvent.Kind.TOOL, "file written", causeId = 1),
                event("3", ConsoleEvent.Kind.AGENT, "Done."),
            ),
        )

        assertEquals(2, entries.size)
    }

    @Test
    fun `entry ids are unique so the lazy list can key on them`() {
        val entries = ChatTranscript.build(
            (1..20).map { event("$it", ConsoleEvent.Kind.AGENT, "line $it") },
        )

        assertEquals(entries.size, entries.map { it.id }.distinct().size)
    }
}
