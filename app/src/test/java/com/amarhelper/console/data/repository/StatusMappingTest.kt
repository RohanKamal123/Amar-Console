package com.amarhelper.console.data.repository

import com.amarhelper.console.domain.model.TaskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusMappingTest {

    @Test
    fun `openhands execution statuses map onto the shared vocabulary`() {
        assertEquals(TaskState.RUNNING, StatusMapping.fromOpenHands("running", "RUNNING"))
        assertEquals(TaskState.QUEUED, StatusMapping.fromOpenHands("idle", "RUNNING"))
        assertEquals(TaskState.WAITING, StatusMapping.fromOpenHands("waiting_for_confirmation", "RUNNING"))
        assertEquals(TaskState.WAITING, StatusMapping.fromOpenHands("paused", "PAUSED"))
        assertEquals(TaskState.COMPLETED, StatusMapping.fromOpenHands("finished", "RUNNING"))
        assertEquals(TaskState.FAILED, StatusMapping.fromOpenHands("error", "RUNNING"))
        assertEquals(TaskState.FAILED, StatusMapping.fromOpenHands("stuck", "RUNNING"))
    }

    @Test
    fun `sandbox status is the fallback when execution status is absent`() {
        assertEquals(TaskState.QUEUED, StatusMapping.fromOpenHands(null, "STARTING"))
        assertEquals(TaskState.RUNNING, StatusMapping.fromOpenHands(null, "RUNNING"))
        assertEquals(TaskState.FAILED, StatusMapping.fromOpenHands(null, "ERROR"))
        assertEquals(TaskState.FAILED, StatusMapping.fromOpenHands(null, "MISSING"))
        assertEquals(TaskState.UNKNOWN, StatusMapping.fromOpenHands(null, null))
    }

    @Test
    fun `start-response statuses are mapped so a new task is never UNKNOWN`() {
        assertEquals(TaskState.QUEUED, StatusMapping.fromOpenHands(null, "WORKING"))
        assertEquals(TaskState.RUNNING, StatusMapping.fromOpenHands(null, "READY"))
    }

    @Test
    fun `an unrecognised status is unknown rather than a guess`() {
        assertEquals(TaskState.UNKNOWN, StatusMapping.fromOpenHands("teleporting", "WARP"))
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
