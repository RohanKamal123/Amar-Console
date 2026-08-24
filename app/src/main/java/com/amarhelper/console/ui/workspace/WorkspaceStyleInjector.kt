package com.amarhelper.console.ui.workspace

import android.content.Context
import android.webkit.WebView
import com.amarhelper.console.core.log.AppLogger

/**
 * Injects the Claude-style stylesheet into an embedded workspace page.
 *
 * This is presentation only: it restyles the page the server already serves, and touches
 * no request, credential or API. If the assets are missing or a selector stops matching
 * after an OpenHands upgrade, the page renders in its own styling — the injector never
 * blocks or rewrites content.
 */
object WorkspaceStyleInjector {

    private const val TAG = "WorkspaceStyle"
    private const val CSS_ASSET = "claude_workspace.css"
    private const val JS_ASSET = "claude_workspace.js"
    private const val CSS_PLACEHOLDER = "__CLAUDE_CSS__"

    @Volatile
    private var cachedScript: String? = null

    /** Builds the injectable script, reading the assets once and caching the result. */
    fun script(context: Context): String? {
        cachedScript?.let { return it }
        return try {
            val css = context.assets.open(CSS_ASSET).bufferedReader().use { it.readText() }
            val js = context.assets.open(JS_ASSET).bufferedReader().use { it.readText() }
            val built = substitute(js, css)
            cachedScript = built
            built
        } catch (e: Exception) {
            // A missing asset must degrade to "unstyled page", never to a crash.
            AppLogger.w(TAG, "Workspace styling unavailable; leaving the page unstyled.", e)
            null
        }
    }

    fun apply(webView: WebView) {
        val script = script(webView.context) ?: return
        webView.evaluateJavascript(script, null)
    }

    /**
     * Puts the stylesheet into the script at the one place that expects it.
     *
     * The substitution used to be a plain `replace`, which rewrites *every* occurrence.
     * The script's own doc comment named the placeholder, so the 15KB quoted stylesheet
     * was spliced into that comment too — and because CSS contains the sequence that
     * closes a block comment, the comment ended early and left stylesheet text in the
     * file as bare JavaScript. The result was `SyntaxError: Invalid or unexpected token`
     * on every page load, reported against the page's own URL because `evaluateJavascript`
     * runs in the document's context. Nothing in the script ran, and the failure was
     * indistinguishable from the page having broken itself.
     *
     * So the count is checked rather than trusted. Anything other than exactly one
     * placeholder throws, the caller degrades to an unstyled page, and the reason is
     * logged instead of being emitted as a corrupt script.
     */
    internal fun substitute(js: String, css: String): String {
        val occurrences = js.split(CSS_PLACEHOLDER).size - 1
        require(occurrences == 1) {
            "Expected exactly one $CSS_PLACEHOLDER placeholder in $JS_ASSET, found $occurrences"
        }
        return js.replace(CSS_PLACEHOLDER, quoteForJs(css))
    }

    /**
     * Renders the stylesheet as a JavaScript string literal.
     *
     * The CSS is a build asset rather than user input, but it still goes through the
     * escaping every generated literal needs: an unescaped backslash, quote, newline or
     * `</script>` sequence would break the script it is embedded in.
     */
    internal fun quoteForJs(css: String): String {
        val escaped = StringBuilder(css.length + 32)
        escaped.append('"')
        css.forEach { character ->
            when (character) {
                '\\' -> escaped.append("\\\\")
                '"' -> escaped.append("\\\"")
                '\n' -> escaped.append("\\n")
                '\r' -> escaped.append("\\r")
                '\u2028' -> escaped.append("\\u2028")
                '\u2029' -> escaped.append("\\u2029")
                '<' -> escaped.append("\\u003C")
                else -> escaped.append(character)
            }
        }
        escaped.append('"')
        return escaped.toString()
    }
}
