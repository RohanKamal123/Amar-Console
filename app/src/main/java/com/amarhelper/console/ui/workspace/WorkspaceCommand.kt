package com.amarhelper.console.ui.workspace

/**
 * A slash command the app offers over the embedded workspace.
 *
 * Every command maps to something already verified against the self-hosted OpenHands
 * server or to an action the app itself owns — none of them depend on reaching into the
 * page's DOM, which is what makes this bar survive an OpenHands upgrade.
 */
enum class WorkspaceCommand(
    val token: String,
    val summary: String,
    val takesArgument: Boolean = false,
    /** Commands that only make sense once a conversation is open. */
    val needsConversation: Boolean = false,
) {
    NEW("/new", "Start a conversation, optionally with a prompt", takesArgument = true),
    STOP("/stop", "Interrupt the agent", needsConversation = true),
    SESSIONS("/sessions", "Back to the conversation list"),
    SETTINGS("/settings", "Open the OpenHands settings page"),
    RELOAD("/reload", "Reload the page"),
    CHROME("/chrome", "Open the current page in Chrome"),
    CLEARCACHE("/clearcache", "Drop the cached page and reload it"),
    PLAIN("/plain", "Reload with no app scripts injected at all"),
    DEVTOOLS("/devtools", "Open developer tools inside the page"),
    DIAG("/diag", "Report what the page says about itself"),
    HELP("/help", "Show these commands");

    companion object {

        /** The palette entries matching what has been typed so far. */
        fun matching(input: String): List<WorkspaceCommand> {
            val trimmed = input.trimStart()
            if (!trimmed.startsWith("/")) return emptyList()
            val token = trimmed.substringBefore(' ').lowercase()
            return entries.filter { it.token.startsWith(token) }
        }

        /**
         * Parses typed input. Returns null when the text is not a command, so the caller
         * can treat it as an ordinary message rather than guessing.
         */
        fun parse(input: String): Parsed? {
            val trimmed = input.trim()
            if (!trimmed.startsWith("/")) return null
            val token = trimmed.substringBefore(' ').lowercase()
            val command = entries.firstOrNull { it.token == token } ?: return null
            val argument = trimmed.substringAfter(' ', missingDelimiterValue = "").trim()
            return Parsed(command, argument.takeIf { it.isNotEmpty() })
        }
    }

    data class Parsed(val command: WorkspaceCommand, val argument: String?)
}

/** What the bar asks the hosting WebView to do. */
sealed interface WorkspaceEffect {
    /** Measure the page from the inside and show the result. */
    data object RunDiagnostics : WorkspaceEffect

    /** Start on-device developer tools inside the page. */
    data object OpenDevTools : WorkspaceEffect

    /**
     * Empties the WebView's HTTP cache, then reloads.
     *
     * The OpenHands frontend is a Vite build: `index.html` names its bundles by content
     * hash, so a cached copy of that document from an earlier deploy points at asset
     * paths the server no longer has. The server answers those with its SPA catch-all —
     * `index.html`, HTTP 200, `text/html` — and a `<script type="module">` importing HTML
     * dies with a syntax error before anything renders. Clearing the cache is the only
     * lever this side of the connection has over that.
     */
    data object ClearCache : WorkspaceEffect

    /**
     * Reloads with every app-side injection switched off, as a control.
     *
     * Turning off Claude styling only stops the stylesheet; the error listener still
     * runs at `onPageStarted`, so "styling off, still blank" never established that the
     * page fails without this app's code in it. One of those injections already turned
     * out to be the source of errors read as the page's own. This separates the two
     * for good: if the page renders after `/plain`, the app is the cause; if it stays
     * blank, nothing this app injects is involved.
     */
    data object ReloadWithoutScripts : WorkspaceEffect

    data class Navigate(val url: String) : WorkspaceEffect
    data object Reload : WorkspaceEffect
    data class OpenExternally(val url: String) : WorkspaceEffect
}

/**
 * Reads the conversation id out of the address the WebView is showing.
 *
 * The OpenHands frontend routes conversations at `conversations/:conversationId`
 * (frontend/src/routes.ts at 0.62.0). Parsing the URL is how the native bar knows which
 * conversation the page is on without reaching into the page itself.
 */
object WorkspaceUrl {

    private val conversationPath = Regex("""/conversations/([^/?#]+)""")

    fun conversationId(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return conversationPath.find(url)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
    }

    fun conversationUrl(baseUrl: String, conversationId: String): String =
        "${baseUrl.trimEnd('/')}/conversations/$conversationId"

    fun sessionsUrl(baseUrl: String): String = baseUrl.trimEnd('/')

    fun settingsUrl(baseUrl: String): String = "${baseUrl.trimEnd('/')}/settings"
}
