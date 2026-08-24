package com.amarhelper.console.ui.workspace

import java.io.File
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
        listOf(
            "root-layout", "app-route", "bodyChildren", "readyState", "styled",
            // A syntax error in an inline script reports no filename, so the probe has
            // to read the scripts back out, and name the engine that rejected them.
            "engine", "inlineScripts", "hasLineSeparator",
            // root-layout present but 381x0 is a collapse, not a boot failure, and a
            // stylesheet the parser dropped causes one without logging anything.
            "styleSheets", "cssRules", "computed", "characterSet",
            // html computes to 0px with a 566px viewport: the height chain is broken at
            // the root. Top-level rule counts cannot show whether the rule that sets it
            // survived, because a Tailwind build nests almost everything.
            "heightRules", "imports",
            // Hydration compares server markup against the client's first render, so the
            // inputs that differ between this WebView and Chrome are what matter now.
            "environment", "languages", "prefers-color-scheme", "cookieEnabled",
        ).forEach {
            assertTrue("probe should report $it", script.contains(it))
        }
    }

    @Test
    fun `the asset probe reports the content type of every import, not just its status`() {
        val script = WorkspaceDiagnostics.ASSET_PROBE_SCRIPT

        // OpenHands answers a missing bundle with index.html at HTTP 200, so the status
        // alone proves nothing; the content type and the first bytes are what separate a
        // real module from the SPA catch-all.
        listOf(
            "content-type", "cache", "no-store", "script[type=\"module\"]", "modulepreload",
            // A stylesheet answered with the catch-all is dropped without an error.
            "stylesheet",
        ).forEach {
            assertTrue("asset probe should use $it", script.contains(it))
        }
        // The console numbers its failures against the served bytes, so the probe has to
        // report those, not the re-serialised DOM — and name any character that would
        // produce "Invalid or unexpected token" rather than guessing at one.
        assertTrue(script.contains("references"))
        assertTrue(script.contains("0x2000"))
        assertTrue(script.contains("0xFEFF"))
        assertTrue(script.contains("suspicious"))
        assertTrue(WorkspaceDiagnostics.PROBE_SCRIPT.contains("__claudeAssetProbe"))
    }

    @Test
    fun `every injected script parses`() {
        // A syntax error here fails silently: evaluateJavascript just returns null, and
        // the diagnostics look like a page that would not answer.
        val node = sequenceOf("/opt/node22/bin/node", "node")
            .firstOrNull { runCatching { ProcessBuilder(it, "--version").start().waitFor() == 0 }.getOrDefault(false) }
        org.junit.Assume.assumeNotNull(node)

        mapOf(
            "ASSET_PROBE_SCRIPT" to WorkspaceDiagnostics.ASSET_PROBE_SCRIPT,
            "PROBE_SCRIPT" to WorkspaceDiagnostics.PROBE_SCRIPT,
            "ERROR_LISTENER_SCRIPT" to WorkspaceDiagnostics.ERROR_LISTENER_SCRIPT,
            "DEVTOOLS_LOADER" to WorkspaceDevTools.LOADER_SCRIPT,
        ).forEach { (name, script) ->
            val file = File.createTempFile(name, ".js").apply { deleteOnExit(); writeText(script) }
            val process = ProcessBuilder(node, "--check", file.absolutePath)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals("$name should parse:\n$output", 0, process.waitFor())
        }
    }

    @Test
    fun `the report carries the build so a stale install is obvious`() {
        val report = WorkspaceDiagnostics.report("""{"url":"/"}""", appVersion = "0.1.0 (1)")

        assertTrue(report.contains("0.1.0 (1)"))
        assertTrue(report.contains("\"url\":\"/\""))
    }

    @Test
    fun `a report from the no-injection control says so`() {
        // Otherwise an empty error list reads as "nothing went wrong" when it actually
        // means the listener that would have caught it was never installed.
        val report = WorkspaceDiagnostics.report("{}", appVersion = "x", injecting = false)

        assertTrue(report.contains("/plain"))
        assertTrue(!WorkspaceDiagnostics.report("{}", appVersion = "x").contains("/plain"))
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
