package com.amarhelper.console.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WorkspaceDiagnosticsTest {

    @Before
    fun setUp() = WorkspaceDiagnostics.clear()

    @Test
    fun `the probe asks for the measurements that distinguish the two failure modes`() {
        val script = WorkspaceDiagnostics.PROBE_SCRIPT

        // Zero-height containers mean the styling collapsed the page; an absent
        // root-layout with an empty body means the app never booted at all.
        listOf("root-layout", "app-route", "bodyChildren", "readyState", "styled").forEach {
            assertTrue("probe should report $it", script.contains(it))
        }
    }

    @Test
    fun `the report carries the build so a stale install is obvious`() {
        val report = WorkspaceDiagnostics.report("""{"url":"/"}""", appVersion = "0.1.0 (1)")

        assertTrue(report.contains("0.1.0 (1)"))
        assertTrue(report.contains("\"url\":\"/\""))
    }

    @Test
    fun `a page that logs nothing says so rather than looking truncated`() {
        val report = WorkspaceDiagnostics.report("{}", appVersion = "x")

        assertTrue(report.contains("console: nothing logged"))
    }

    @Test
    fun `a page that never answers the probe is reported, not hidden`() {
        val report = WorkspaceDiagnostics.report(null, appVersion = "x")

        assertTrue(report.contains("no response"))
    }

    @Test
    fun `the buffer is bounded so a page logging in a loop cannot grow it`() {
        repeat(200) { WorkspaceDiagnostics.recordLine("E boom $it") }

        assertEquals(40, WorkspaceDiagnostics.recent().size)
        assertTrue(WorkspaceDiagnostics.recent().last().contains("199"))
    }

    @Test
    fun `errors and warnings are preferred over chatter when something failed`() {
        WorkspaceDiagnostics.recordLine("L just some logging")
        WorkspaceDiagnostics.recordLine("E TypeError: undefined is not a function")

        val report = WorkspaceDiagnostics.report("{}", appVersion = "x")

        assertTrue(report.contains("TypeError"))
        assertTrue(!report.contains("just some logging"))
    }
}
