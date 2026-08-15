package com.amarhelper.console.ui.navigation

import android.net.Uri

object Routes {
    const val SPLASH = "splash"
    const val DASHBOARD = "dashboard"
    const val NEW_TASK = "task/new"
    const val SESSIONS = "sessions"
    const val SERVICES = "services"
    const val SETTINGS = "settings"

    private const val CONSOLE_BASE = "console"
    const val CONSOLE = "$CONSOLE_BASE/{provider}/{sessionId}"

    fun console(provider: String, sessionId: String): String =
        "$CONSOLE_BASE/$provider/${Uri.encode(sessionId)}"

    object Args {
        const val PROVIDER = "provider"
        const val SESSION_ID = "sessionId"
    }
}
