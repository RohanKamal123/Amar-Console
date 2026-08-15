package com.amarhelper.console.data.remote.openhands

import com.amarhelper.console.domain.model.ConsoleEvent
import com.amarhelper.console.domain.model.TaskState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenHandsEventMapperTest {
    private fun event(value: String) = Json.parseToJsonElement(value).jsonObject

    @Test
    fun `message actions are transcript entries`() {
        val result = OpenHandsEventMapper.map(
            event("""{"id":4,"source":"agent","action":"message","message":"**Done**"}"""),
        ) as OpenHandsEventMapper.Result.Transcript

        assertEquals(ConsoleEvent.Kind.AGENT, result.event.kind)
        assertEquals("**Done**", result.event.text)
    }

    @Test
    fun `state observations become status and not transcript`() {
        val result = OpenHandsEventMapper.map(
            event("""{"id":9,"source":"environment","observation":"agent_state_changed","extras":{"agent_state":"awaiting_user_input"}}"""),
        ) as OpenHandsEventMapper.Result.Status

        assertEquals("Agent: waiting", result.label)
        assertEquals(TaskState.WAITING, result.state)
    }

    @Test
    fun `internal recall events are ignored`() {
        assertTrue(
            OpenHandsEventMapper.map(
                event("""{"id":2,"source":"environment","observation":"recall","content":"internal"}"""),
            ) is OpenHandsEventMapper.Result.Ignore,
        )
    }

    @Test
    fun `command action and observation merge into one tool card`() {
        val events = listOf(
            event("""{"id":10,"source":"agent","action":"run","args":{"command":"git diff"},"message":"Running"}"""),
            event("""{"id":11,"cause":10,"source":"environment","observation":"run","content":"output","success":true}"""),
        )

        val transcript = OpenHandsEventMapper.transcript(events)
        assertEquals(1, transcript.size)
        assertEquals("git diff", transcript.single().tool?.command)
        assertEquals("output", transcript.single().tool?.output)
        assertEquals(true, transcript.single().tool?.succeeded)
    }
}
