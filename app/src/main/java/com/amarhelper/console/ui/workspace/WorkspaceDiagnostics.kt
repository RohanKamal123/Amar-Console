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
     * Clearing the cache changed nothing, so a stale document is not the explanation.
     * What remains unread is the served bytes themselves: the console reports the
     * failures at line 9 of the document, but the earlier probe numbered lines from
     * `outerHTML`, which is the re-serialised DOM and does not share the raw file's line
     * count — the doctype and everything before `<html>` are simply not in it. So the
     * document is fetched and numbered as served, and scanned for the control and
     * invisible characters that produce exactly "Invalid or unexpected token".
     *
     * Stylesheets are fetched alongside the modules. A stylesheet answered with the SPA
     * catch-all is dropped silently by the parser, and the frontend's `h-full` chain
     * collapses to zero height without a single error — which is what a `root-layout`
     * measuring 381x0 looks like.
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
          var tags = document.querySelectorAll(
            'script[src], link[rel="modulepreload"][href], link[rel="stylesheet"][href]'
          );
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
                  // A stylesheet cut short parses cleanly up to the cut and drops
                  // everything after it, which no status or content type reveals.
                  bytes: body.length,
                  head: body.slice(0, 60).replace(/\s+/g, " ")
                });
              });
            }).catch(function (error) {
              results.push({ url: url.replace(location.origin, ""), status: "failed", type: String(error) });
            });
          }

          var jobs = [];
          for (var k = 0; k < urls.length && k < 6; k++) jobs.push(describe(urls[k]));

          // The frontend reads this before it can render anything. Assets loading says
          // nothing about it: it is a different path, with cookies and credentials.
          jobs.push(fetch("/api/options/config", { cache: "no-store", credentials: "include" })
            .then(function (response) {
              return response.text().then(function (payload) {
                results.push({
                  url: "/api/options/config",
                  status: response.status,
                  type: response.headers.get("content-type") || "(none)",
                  bytes: payload.length,
                  head: payload.slice(0, 80).replace(/\s+/g, " ")
                });
              });
            })
            .catch(function (error) {
              results.push({ url: "/api/options/config", status: "failed", type: String(error) });
            }));

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
              // Numbered as served. The console's line 9 means line 9 of this, which
              // outerHTML cannot show: it starts at <html>, dropping the doctype.
              var lines = html.split("\n");
              var served = [];
              for (var n = 0; n < lines.length && n < 14; n++) {
                served.push((n + 1) + " [" + lines[n].length + "] " + lines[n].slice(0, 110));
              }

              // "Invalid or unexpected token" is what a stray character produces. This
              // finds one by code point rather than guessing which one it might be:
              // control characters, the invisible general-punctuation block (U+2028 and
              // U+2029 among them), a byte-order mark, a non-breaking space. Emoji are
              // legitimately present and are not flagged.
              function suspicious(code) {
                if (code < 32 && code !== 9 && code !== 10 && code !== 13) return true;
                if (code >= 0x2000 && code <= 0x206F) return true;
                return code === 0xFEFF || code === 0x00A0;
              }
              var odd = [];
              var line = 1;
              var column = 1;
              for (var p = 0; p < html.length && odd.length < 12; p++) {
                var code = html.charCodeAt(p);
                if (code === 10) { line++; column = 1; continue; }
                if (suspicious(code)) {
                  odd.push({ line: line, column: column, code: "U+" + code.toString(16).toUpperCase() });
                }
                column++;
              }

              results.push({
                url: "(server's current document)",
                bytes: html.length,
                references: references,
                lines: served,
                odd: odd.length > 0 ? odd : "none"
              });
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
          // root-layout exists and measures 381x0: the markup arrived and something
          // flattened it. A stylesheet the parser dropped does exactly that to the
          // frontend's h-full chain, and leaves no error behind to find.
          // html computes to 0px while the viewport is 566 tall, so the chain is broken
          // at the very top. The frontend's h-full percentages need an explicit
          // html/body height, and the only question left is whether the applied
          // stylesheet actually carries that rule. Counting top-level rules cannot say:
          // a Tailwind build nests nearly everything inside @layer and @media, so the
          // walk goes all the way down and reports the matching rules verbatim.
          var heightRules = [];
          var imports = [];
          // root-layout carries the class `h-screen` (height:100vh). If that rule is
          // absent or overridden in the served CSS, the container has no height of its
          // own and collapses — which is a page-CSS fault. So capture the rule verbatim
          // rather than infer its presence from a count.
          var hScreenRule = null;
          function walk(rules, counter) {
            for (var r = 0; r < rules.length; r++) {
              var rule = rules[r];
              counter.deep++;
              if (rule.href !== undefined && rule.media !== undefined && rule.styleSheet !== undefined) {
                // An @import that did not resolve leaves styleSheet null. This one
                // points at a public font host, from a phone on a VPN-only network.
                imports.push({ href: String(rule.href).slice(0, 70), loaded: !!rule.styleSheet });
              }
              var selector = rule.selectorText || "";
              if (
                selector &&
                /(^|,)\s*(html|body|:root)\b/.test(selector) &&
                /height\s*:/.test(rule.cssText) &&
                heightRules.length < 10
              ) {
                heightRules.push(rule.cssText.slice(0, 140).replace(/\s+/g, " "));
              }
              if (hScreenRule === null && /\.h-screen\b/.test(selector)) {
                hScreenRule = rule.cssText.slice(0, 140).replace(/\s+/g, " ");
              }
              if (rule.cssRules && rule.cssRules.length) walk(rule.cssRules, counter);
            }
          }

          var sheets = [];
          for (var s = 0; s < document.styleSheets.length; s++) {
            var sheet = document.styleSheets[s];
            var rules;
            var counter = { deep: 0 };
            try {
              rules = sheet.cssRules ? sheet.cssRules.length : 0;
              if (sheet.cssRules) walk(sheet.cssRules, counter);
            } catch (blocked) {
              rules = "unreadable";
            }
            sheets.push({
              href: (sheet.href || "(inline)").replace(location.origin, ""),
              rules: rules,
              deep: counter.deep
            });
          }
          function heightOf(node) {
            if (!node) return "absent";
            var style = getComputedStyle(node);
            return style.height + " " + style.display + " overflow:" + style.overflowY;
          }

          // Hydration compares what the server rendered against what the client renders
          // first. These are the inputs that can differ between this WebView and Chrome
          // on the same device — the browser's language list drives i18next, the colour
          // scheme drives themed markup, and blocked storage changes what the app reads
          // before its first paint. A difference here is a reason for a mismatch; the
          // absence of one rules the whole class out.
          function attempt(work) {
            try { return work(); } catch (error) { return String(error).slice(0, 60); }
          }
          var environment = {
            language: navigator.language,
            languages: (navigator.languages || []).join(","),
            colorScheme: attempt(function () {
              return window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
            }),
            timeZone: attempt(function () { return Intl.DateTimeFormat().resolvedOptions().timeZone; }),
            cookies: navigator.cookieEnabled,
            storage: attempt(function () {
              localStorage.setItem("__claude_probe", "1");
              localStorage.removeItem("__claude_probe");
              return "ok";
            }),
            lang: document.documentElement.getAttribute("lang"),
            dir: document.documentElement.getAttribute("dir"),
            rootClass: (document.documentElement.className || "(none)").slice(0, 80)
          };

          var body = document.body;

          // The contradiction to resolve: the fixed-position toast layer measures a
          // full-viewport height, so the containing block is ~566 tall — yet root-layout,
          // which asks for height:100vh, computes to 0. Either 100vh resolves to 0 for
          // in-flow elements in this WebView (a WebView fault), or the .h-screen rule
          // never applies and the container is really height:auto collapsing to nothing
          // (a page-CSS fault). Measure both directly with throwaway nodes rather than
          // reason about which: a bare inline height:100vh, and the class the container
          // actually uses. clientHeight is the layout viewport that 100vh resolves against.
          function measuredHeight(apply) {
            if (!body) return "no body";
            var probe = document.createElement("div");
            probe.style.cssText = "position:absolute;top:0;left:0;width:1px;visibility:hidden;";
            apply(probe);
            body.appendChild(probe);
            var height = probe.offsetHeight;
            body.removeChild(probe);
            return height;
          }
          var rootLayoutNode = document.querySelector('[data-testid="root-layout"]');
          var layout = {
            innerHeight: window.innerHeight,
            clientHeight: document.documentElement.clientHeight,
            visualViewport: window.visualViewport ? Math.round(window.visualViewport.height) : "n/a",
            vh100: measuredHeight(function (node) { node.style.height = "100vh"; }),
            hScreenClass: measuredHeight(function (node) { node.className = "h-screen"; }),
            rootInlineHeight: rootLayoutNode ? (rootLayoutNode.style.height || "(none)") : "absent"
          };

          // 13 children measuring nothing, and no idea what any of them are. This is the
          // difference between "the app rendered a shell that hydration then emptied"
          // and "the server sent a shell the client never filled in" — which decides
          // where to look next, and cannot be read from a count.
          var outline = [];
          var children = body ? body.children : [];
          for (var c = 0; c < children.length && c < 16; c++) {
            var child = children[c];
            var childRect = child.getBoundingClientRect();
            // SVG elements carry an SVGAnimatedString here, not a string.
            var className = child.className && child.className.toString
              ? child.className.toString()
              : "";
            outline.push({
              tag: child.tagName.toLowerCase(),
              testId: child.getAttribute("data-testid") || "",
              id: child.id || "",
              cls: className.slice(0, 40),
              kids: child.childElementCount,
              box: Math.round(childRect.width) + "x" + Math.round(childRect.height),
              text: (child.textContent || "").trim().slice(0, 40).replace(/\s+/g, " ")
            });
          }

          return JSON.stringify({
            engine: engine,
            environment: environment,
            charset: document.characterSet,
            contentType: document.contentType,
            styleSheets: sheets,
            heightRules: heightRules.length > 0 ? heightRules : "none applied",
            hScreenRule: hScreenRule || "absent",
            layout: layout,
            imports: imports.length > 0 ? imports : "none",
            computed: {
              html: heightOf(document.documentElement),
              body: heightOf(body),
              rootLayout: heightOf(document.querySelector('[data-testid="root-layout"]'))
            },
            url: location.pathname,
            title: document.title,
            readyState: document.readyState,
            viewport: window.innerWidth + "x" + window.innerHeight,
            bodyScroll: body ? body.scrollWidth + "x" + body.scrollHeight : "no body",
            bodyChildren: body ? body.children.length : 0,
            bodyOutline: outline,
            rootLayout: box('[data-testid="root-layout"]'),
            appRoute: box('[data-testid="app-route"]'),
            chatInput: box('[data-testid="chat-input"]'),
            styled: document.documentElement.hasAttribute("data-claude-style"),
            styleTag: !!document.getElementById("claude-workspace-style"),
            inlineScripts: inline,
            assets: window.__claudeAssetProbe || "not run",
            errors: window.__claudeErrors || "listener missing"
          });
        })();
    """

    /** Formats a probe result and the console log into something readable on a phone. */
    fun report(probeJson: String?, appVersion: String, injecting: Boolean = true): String {
        val console = problems().ifEmpty { recent() }
        return buildString {
            append("build: ").append(appVersion).append('\n')
            // After /plain the error listener is not installed either, so an empty
            // `errors` list means "not watched", not "nothing went wrong".
            if (!injecting) append("app scripts: none injected (/plain)\n")
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
