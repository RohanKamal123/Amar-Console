package com.amarhelper.console.data.repository

import com.amarhelper.console.domain.model.TaskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusMappingTest {

    @Test
    fun `self-hosted conversation statuses map onto the shared vocabulary`() {
        assertEquals(TaskState.QUEUED, StatusMapping.fromOpenHands("STARTING", null))
        assertEquals(TaskState.RUNNING, StatusMapping.fromOpenHands("RUNNING", "STATUS\$READY"))
        assertEquals(TaskState.CANCELLED, StatusMapping.fromOpenHands("ARCHIVED", null))
    }

    @Test
    fun `STOPPED is not reported as success`() {
        // The server says only that the conversation is not running. Claiming the task
        // completed would be an invention.
        assertEquals(TaskState.STOPPED, StatusMapping.fromOpenHands("STOPPED", null))
        assertTrue(TaskState.STOPPED.isTerminal)
    }

    @Test
    fun `a failed runtime beats a conversation that still claims to be running`() {
        assertEquals(TaskState.FAILED, StatusMapping.fromOpenHands("RUNNING", "STATUS\$ERROR"))
        assertEquals(
            TaskState.FAILED,
            StatusMapping.fromOpenHands("RUNNING", "STATUS\$ERROR_LLM_OUT_OF_CREDITS"),
        )
        assertEquals(
            TaskState.FAILED,
            StatusMapping.fromOpenHands("RUNNING", "STATUS\$ERROR_RUNTIME_DISCONNECTED"),
        )
    }

    @Test
    fun `a runtime that is still booting is queued rather than running`() {
        assertEquals(TaskState.QUEUED, StatusMapping.fromOpenHands("RUNNING", "STATUS\$BUILDING_RUNTIME"))
        assertEquals(TaskState.QUEUED, StatusMapping.fromOpenHands("RUNNING", "STATUS\$STARTING_RUNTIME"))
        assertEquals(TaskState.QUEUED, StatusMapping.fromOpenHands("RUNNING", "STATUS\$SETTING_UP_WORKSPACE"))
    }

    @Test
    fun `terminal and active states are classified consistently`() {
        assertTrue(TaskState.COMPLETED.isTerminal)
        assertTrue(TaskState.FAILED.isTerminal)
        assertTrue(TaskState.CANCELLED.isTerminal)
        assertTrue(TaskState.RUNNING.isActive)
        assertTrue(TaskState.QUEUED.isActive)
        assertTrue(TaskState.WAITING.isActive)
    }

    @Test
    fun `iso timestamps parse and malformed ones do not throw`() {
        assertEquals(1736937000000L, StatusMapping.parseIsoTimestamp("2025-01-15T10:30:00Z"))
        assertNull(StatusMapping.parseIsoTimestamp("yesterday"))
        assertNull(StatusMapping.parseIsoTimestamp(null))
    }

    @Test
    fun `epoch seconds are promoted to milliseconds`() {
        assertEquals(1736937000000L, StatusMapping.normalizeEpoch(1736937000L))
        assertEquals(1736937000000L, StatusMapping.normalizeEpoch(1736937000000L))
        assertNull(StatusMapping.normalizeEpoch(null))
    }
}
