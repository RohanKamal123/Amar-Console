package com.amarhelper.console.ui.workspace

import android.webkit.ConsoleMessage
import java.util.ArrayDeque

/**
 * Captures what the embedded page reports about itself.
 *
 * A page that renders blank gives nothing away from the outside: the WebView reports a
 * successful load either way. The two things that actually distinguish "the styling
 * collapsed it" from "the app never booted" are the page's own JavaScript console and
 * the measured size of its root elements, so both are collected here and shown on
 * demand via the `/diag` command.
 *
 * Diagnostics stay on the device. Nothing is uploaded, and the buffer is bounded so a
 * page that logs in a loop cannot grow it without limit.
 */
object WorkspaceDiagnostics {

    private const val MAX_MESSAGES = 40

    private val messages = ArrayDeque<String>()

    @Synchronized
    fun record(message: ConsoleMessage) {
        val line = buildString {
            append(message.messageLevel().name.take(1))
            append(' ')
            append(message.message().take(300))
            message.sourceId()?.takeIf { it.isNotBlank() }?.let { source ->
                append("  (")
                append(source.substringAfterLast('/').take(40))
                append(':')
                append(message.lineNumber())
                append(')')
            }
        }
        recordLine(line)
    }

    /** Records an already-formatted line. Used by [record] and by tests. */
    @Synchronized
    fun recordLine(line: String) {
        if (messages.size >= MAX_MESSAGES) messages.removeFirst()
        messages.addLast(line)
    }

    @Synchronized
    fun recent(): List<String> = messages.toList()

    @Synchronized
    fun clear() = messages.clear()

    /** Errors and warnings only — what matters when a page fails to appear. */
    @Synchronized
    fun problems(): List<String> = messages.filter { it.startsWith("E ") || it.startsWith("W ") }

    /**
     * Measures the page from the inside.
     *
     * Returns JSON so the result can be shown verbatim rather than interpreted here —
     * when a diagnosis has already been wrong once, the raw numbers are worth more than
     * a summary of them.
     */
    const val PROBE_SCRIPT: String = """
        (function () {
          function box(selector) {
            var element = document.querySelector(selector);
            if (!element) return "absent";
            var rect = element.getBoundingClientRect();
            return Math.round(rect.width) + "x" + Math.round(rect.height);
          }
          var body = document.body;
          return JSON.stringify({
            url: location.pathname,
            title: document.title,
            readyState: document.readyState,
            viewport: window.innerWidth + "x" + window.innerHeight,
            bodyScroll: body ? body.scrollWidth + "x" + body.scrollHeight : "no body",
            bodyChildren: body ? body.children.length : 0,
            rootLayout: box('[data-testid="root-layout"]'),
            appRoute: box('[data-testid="app-route"]'),
            chatInput: box('[data-testid="chat-input"]'),
            styled: document.documentElement.hasAttribute("data-claude-style"),
            styleTag: !!document.getElementById("claude-workspace-style")
          });
        })();
    """

    /** Formats a probe result and the console log into something readable on a phone. */
    fun report(probeJson: String?, appVersion: String): String {
        val console = problems().ifEmpty { recent() }
        return buildString {
            append("build: ").append(appVersion).append('\n')
            append("page: ").append(probeJson?.trim('"')?.replace("\\\"", "\"") ?: "no response").append("\n\n")
            if (console.isEmpty()) {
                append("console: nothing logged")
            } else {
                append("console (").append(console.size).append("):\n")
                console.takeLast(12).forEach { append("• ").append(it).append('\n') }
            }
        }
    }
}
