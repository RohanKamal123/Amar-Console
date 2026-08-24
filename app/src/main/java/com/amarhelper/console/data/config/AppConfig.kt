package com.amarhelper.console.data.config

/**
 * Which backend service a URL or credential belongs to.
 *
 * Postgres and Redis are deliberately absent: a phone must not hold database
 * credentials or open a DB port across the network. Their health is reported by
 * the gateway's health endpoint instead (see ARCHITECTURE.md).
 */
enum class ServiceId(val displayName: String) {
    IDE("IDE"),
    OPEN_HANDS("OpenHands"),
    OPEN_CODE("OpenCode"),
    LITE_LLM("LiteLLM"),
    GATEWAY("Gateway / API"),
}

/** Deployment target. Only a label — it selects which stored URL set is active. */
enum class Environment(val label: String) {
    DEV("Development"),
    STAGING("Staging"),
    PRODUCTION("Production"),
}

/**
 * The complete runtime configuration of the app.
 *
 * Nothing here is compiled into the binary. Every value is entered by the user in
 * Settings and persisted locally, so the same APK works against any deployment.
 */
data class AppConfig(
    val environment: Environment = Environment.PRODUCTION,
    val ideUrl: String = "",
    val openHandsUrl: String = "",
    val openCodeUrl: String = "",
    val liteLlmUrl: String = "",
    val gatewayUrl: String = "",
    val pollIntervalSeconds: Int = DEFAULT_POLL_SECONDS,
    /**
     * Path probed on the LiteLLM host. Configurable because deployments often run a
     * custom router in place of the upstream proxy, which need not serve the documented
     * health route.
     */
    val liteLlmHealthPath: String = DEFAULT_LITELLM_HEALTH_PATH,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /**
     * Render OpenHands and OpenCode inside the app with Claude-style mobile styling.
     * Turning this off returns them to the system browser, which renders the stock
     * desktop interface and cannot be styled.
     */
    val claudeStyleWorkspaces: Boolean = true,
    /**
     * Render workspaces in the app rather than the system browser. Kept separate from
     * [claudeStyleWorkspaces] so a rendering problem can be told apart from a styling
     * one: in-app with styling off is the control case.
     */
    val openWorkspacesInApp: Boolean = true,
    val verboseNetworkLogging: Boolean = false,
) {
    fun urlFor(service: ServiceId): String = when (service) {
        ServiceId.IDE -> ideUrl
        ServiceId.OPEN_HANDS -> openHandsUrl
        ServiceId.OPEN_CODE -> openCodeUrl
        ServiceId.LITE_LLM -> liteLlmUrl
        ServiceId.GATEWAY -> gatewayUrl
    }

    fun isConfigured(service: ServiceId): Boolean = urlFor(service).isNotBlank()

    /** True when at least one service has somewhere to talk to. */
    val hasAnyService: Boolean
        get() = ServiceId.entries.any { isConfigured(it) }

    companion object {
        const val DEFAULT_POLL_SECONDS = 5
        const val DEFAULT_LITELLM_HEALTH_PATH = "health/readiness"
        const val MIN_POLL_SECONDS = 2
        const val MAX_POLL_SECONDS = 60
    }
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }
