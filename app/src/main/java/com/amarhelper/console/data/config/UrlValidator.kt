package com.amarhelper.console.data.config

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Outcome of checking a URL the user typed into Settings. */
sealed interface UrlValidation {
    data class Valid(val normalized: String, val warning: String? = null) : UrlValidation
    data class Invalid(val reason: String) : UrlValidation
}

/**
 * Validates and normalizes a service URL before it is stored.
 *
 * Cleartext http:// is accepted only for hosts that cannot be reached from the public
 * internet — Tailscale MagicDNS names, loopback, and RFC1918/CGNAT addresses — which
 * mirrors what res/xml/network_security_config.xml permits at the platform level.
 * Anything else must be https://, and the user is told why.
 */
object UrlValidator {

    private val privateHostPatterns = listOf(
        Regex("""^localhost$""", RegexOption.IGNORE_CASE),
        Regex("""^127\.\d{1,3}\.\d{1,3}\.\d{1,3}$"""),
        Regex("""^10\.\d{1,3}\.\d{1,3}\.\d{1,3}$"""),
        Regex("""^192\.168\.\d{1,3}\.\d{1,3}$"""),
        Regex("""^172\.(1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3}$"""),
        // Tailscale / CGNAT range 100.64.0.0/10
        Regex("""^100\.(6[4-9]|[7-9]\d|1[01]\d|12[0-7])\.\d{1,3}\.\d{1,3}$"""),
    )

    private val magicDnsSuffix = Regex("""\.ts\.net$""", RegexOption.IGNORE_CASE)

    fun validate(raw: String): UrlValidation {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return UrlValidation.Invalid("Enter a URL.")

        // A bare host defaults to the scheme that host is likely to speak. Tailnet peers,
        // LAN addresses and loopback serve plain HTTP — these services ship without TLS —
        // so defaulting them to https produced a handshake failure against a working
        // backend. Everything else still defaults to https.
        val withScheme = when {
            trimmed.contains("://") -> trimmed
            isPrivateOrTailnet(trimmed.substringBefore(':').substringBefore('/')) -> "http://$trimmed"
            else -> "https://$trimmed"
        }
        val url = withScheme.toHttpUrlOrNull()
            ?: return UrlValidation.Invalid("That isn't a valid URL.")

        if (url.scheme != "http" && url.scheme != "https") {
            return UrlValidation.Invalid("Only http and https are supported.")
        }

        val host = url.host
        if (host.isBlank()) return UrlValidation.Invalid("The URL has no host.")

        // Strip any trailing slash; Retrofit base URLs are rebuilt with one.
        val normalized = url.newBuilder().build().toString().trimEnd('/')

        // This is the app's only gate on cleartext, so it is the one that has to hold:
        // http is allowed to hosts that cannot be reached from the public internet, and
        // refused everywhere else. A tailnet address (100.64.0.0/10) is allowed, since
        // that is the address Tailscale shows first and the traffic is already
        // WireGuard-encrypted end to end.
        if (url.scheme == "http" && !isPrivateOrTailnet(host)) {
            return UrlValidation.Invalid(
                "Plain http is only allowed for private or tailnet hosts. Use https for $host.",
            )
        }

        return UrlValidation.Valid(normalized)
    }

    /** True for loopback, RFC1918, the Tailscale CGNAT range, and MagicDNS names. */
    private fun isPrivateOrTailnet(host: String): Boolean =
        privateHostPatterns.any { it.matches(host) } || magicDnsSuffix.containsMatchIn(host)
}
