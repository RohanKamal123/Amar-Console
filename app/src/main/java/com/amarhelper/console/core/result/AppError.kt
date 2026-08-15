package com.amarhelper.console.core.result

/**
 * Every failure the app can surface, expressed in domain terms.
 *
 * The UI switches on these; it never sees an [okhttp3.Response] or a raw exception.
 * Each case carries enough information for a screen to explain what went wrong and
 * decide whether offering "Retry" makes sense.
 */
sealed interface AppError {

    /** Text shown to the user. Never contains headers, tokens or request bodies. */
    val message: String

    /** Whether a plain retry of the same request is reasonable. */
    val retryable: Boolean

    /** No route to the host: airplane mode, VPN down, DNS failure, connection refused. */
    data class Offline(
        override val message: String = "Can't reach the backend. Check that your VPN is connected.",
    ) : AppError {
        override val retryable: Boolean = true
    }

    /** The request exceeded a client-side timeout (HTTP 408 maps here too). */
    data class Timeout(
        override val message: String = "The backend took too long to respond.",
    ) : AppError {
        override val retryable: Boolean = true
    }

    /** 401 — the stored credential is missing, expired or rejected. */
    data class Unauthorized(
        override val message: String = "Authentication failed. Update the token in Settings.",
    ) : AppError {
        override val retryable: Boolean = false
    }

    /** 403 — authenticated, but not permitted. */
    data class Forbidden(
        override val message: String = "This token is not allowed to perform that action.",
    ) : AppError {
        override val retryable: Boolean = false
    }

    /** 404 — the route does not exist on the configured backend. */
    data class NotFound(
        override val message: String = "The backend does not expose that endpoint.",
    ) : AppError {
        override val retryable: Boolean = false
    }

    /** 409 — the resource changed underneath us. */
    data class Conflict(
        override val message: String = "That resource was modified by someone else.",
    ) : AppError {
        override val retryable: Boolean = false
    }

    /** 429 — throttled. [retryAfterSeconds] comes from the Retry-After header when present. */
    data class RateLimited(
        val retryAfterSeconds: Long? = null,
        override val message: String = "Too many requests. Wait a moment before retrying.",
    ) : AppError {
        override val retryable: Boolean = true
    }

    /** 500, 502, 503, 504 — the backend failed to handle a valid request. */
    data class ServerError(
        val code: Int,
        override val message: String = "The backend reported an error (HTTP $code).",
    ) : AppError {
        override val retryable: Boolean = true
    }

    /** Any other non-2xx status. */
    data class Http(
        val code: Int,
        override val message: String = "Unexpected response from the backend (HTTP $code).",
    ) : AppError {
        override val retryable: Boolean = false
    }

    /** The response was not the JSON we expect — wrong service on that port, or a proxy error page. */
    data class Malformed(
        override val message: String = "The response wasn't in the expected format. Check the service URL.",
    ) : AppError {
        override val retryable: Boolean = false
    }

    /** The user has not finished configuring this service. */
    data class NotConfigured(
        val serviceName: String,
        override val message: String = "$serviceName has no URL configured yet.",
    ) : AppError {
        override val retryable: Boolean = false
    }

    /**
     * The capability exists in this app's domain model but the configured backend
     * has no endpoint for it. Surfaced honestly rather than faked as success.
     */
    data class Unsupported(
        val capability: String,
        override val message: String = "$capability isn't supported by the configured backend.",
    ) : AppError {
        override val retryable: Boolean = false
    }

    /** The caller's scope was cancelled — the user navigated away or hit Cancel. */
    data object Cancelled : AppError {
        override val message: String = "Cancelled."
        override val retryable: Boolean = false
    }
}
