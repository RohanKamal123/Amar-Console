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
    /**
     * Installed as the page starts, before its own scripts run.
     *
     * The console reports these failures without a filename, which is what has made
     * them impossible to place. An error listener sees the source, line and column the
     * console omits.
     */
    const val ERROR_LISTENER_SCRIPT: String = """
        (function () {
          if (window.__claudeErrors) return;
          window.__claudeErrors = [];
          window.addEventListener("error", function (event) {
            window.__claudeErrors.push({
              message: String(event.message || ""),
              source: String(event.filename || "(inline)"),
              line: event.lineno,
              column: event.colno
            });
          }, true);
        })();
    """

    /**
     * Fetches the page's own module bundles and reports what comes back.
     *
     * The console says `Uncaught SyntaxError: Invalid or unexpected token` with no
     * filename, which is what a `<script type="module">` reports when the thing it
     * imported is not JavaScript at all. OpenHands serves its frontend through
     * `SPAStaticFiles`, whose catch-all answers any unknown path with `index.html` at
     * HTTP 200 — so a bundle whose content hash no longer exists comes back as HTML with
     * a success status, and every check from outside the page looks fine.
     *
     * This distinguishes the two cases outright: the content type of each import, plus
     * the asset paths a freshly fetched document names, next to the ones this document
     * actually loaded. If they differ, the loaded document came from cache.
     *
     * Asynchronous by necessity — `evaluateJavascript` cannot wait on a promise — so the
     * result is parked on `window.__claudeAssetProbe` and read back by [PROBE_SCRIPT].
     */
    const val ASSET_PROBE_SCRIPT: String = """
        (function () {
          window.__claudeAssetProbe = "running";
          var urls = [];
          function add(value) {
            if (!value) return;
            try {
              var absolute = new URL(value, location.href).href;
              if (urls.indexOf(absolute) < 0) urls.push(absolute);
            } catch (error) { /* not a resolvable specifier */ }
          }

          // Imports named by the inline module script — the ones that fail.
          var inline = document.querySelectorAll('script[type="module"]:not([src])');
          for (var i = 0; i < inline.length; i++) {
            var text = inline[i].textContent || "";
            var specifier = /(?:from|import)\s*["']([^"']+)["']/g;
            var found;
            while ((found = specifier.exec(text)) !== null) add(found[1]);
          }
          var tags = document.querySelectorAll('script[src], link[rel="modulepreload"][href]');
          for (var j = 0; j < tags.length; j++) {
            add(tags[j].getAttribute("src") || tags[j].getAttribute("href"));
          }

          var results = [];
          function describe(url) {
            return fetch(url, { cache: "no-store" }).then(function (response) {
              return response.text().then(function (body) {
                results.push({
                  url: url.replace(location.origin, ""),
                  status: response.status,
                  type: response.headers.get("content-type") || "(none)",
                  head: body.slice(0, 60).replace(/\s+/g, " ")
                });
              });
            }).catch(function (error) {
              results.push({ url: url.replace(location.origin, ""), status: "failed", type: String(error) });
            });
          }

          var jobs = [];
          for (var k = 0; k < urls.length && k < 6; k++) jobs.push(describe(urls[k]));

          // What the server says the document should reference right now.
          jobs.push(fetch(location.href, { cache: "no-store" })
            .then(function (response) { return response.text(); })
            .then(function (html) {
              var references = [];
              var asset = /["'](\/assets\/[^"']+)["']/g;
              var hit;
              while ((hit = asset.exec(html)) !== null && references.length < 6) {
                if (references.indexOf(hit[1]) < 0) references.push(hit[1]);
              }
              results.push({ url: "(server's current document)", references: references });
            })
            .catch(function (error) {
              results.push({ url: "(server's current document)", status: String(error) });
            }));

          Promise.all(jobs).then(function () { window.__claudeAssetProbe = results; });
          return urls.length;
        })();
    """

    const val PROBE_SCRIPT: String = """
        (function () {
          function box(selector) {
            var element = document.querySelector(selector);
            if (!element) return "absent";
            var rect = element.getBoundingClientRect();
            return Math.round(rect.width) + "x" + Math.round(rect.height);
          }
          // The engine version decides whether a modern bundle can even be parsed.
          var engine = "unknown";
          var match = navigator.userAgent.match(/Chrome\/(\d+)/);
          if (match) engine = "Chrome " + match[1];

          // An inline script that fails to parse leaves no filename in the console,
          // so the only way to see what broke is to read the scripts back out.
          var inline = [];
          var scripts = document.querySelectorAll("script:not([src])");
          for (var i = 0; i < scripts.length; i++) {
            var text = scripts[i].textContent || "";
            inline.push({
              index: i,
              type: scripts[i].getAttribute("type") || "(classic)",
              length: text.length,
              // Characters that older engines reject inside a string literal.
              hasLineSeparator: /[\u2028\u2029]/.test(text),
              head: text.slice(0, 90).replace(/\s+/g, " ")
            });
          }
          var body = document.body;
          return JSON.stringify({
            engine: engine,
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
            styleTag: !!document.getElementById("claude-workspace-style"),
            inlineScripts: inline,
            assets: window.__claudeAssetProbe || "not run",
            errors: window.__claudeErrors || "listener missing",
            // The failures report line 9 of something unnamed; this is what the served
            // document actually has there.
            documentLines: (document.documentElement.outerHTML || "")
              .split("\n").slice(5, 12)
              .map(function (line, offset) { return (offset + 6) + ": " + line.slice(0, 120); })
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
