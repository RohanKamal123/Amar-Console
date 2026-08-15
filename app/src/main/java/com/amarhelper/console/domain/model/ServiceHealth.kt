package com.amarhelper.console.domain.model

import com.amarhelper.console.data.config.ServiceId

enum class HealthState {
    ONLINE,
    DEGRADED,
    OFFLINE,
    NOT_CONFIGURED,
    UNKNOWN,
}

/**
 * The result of one health probe.
 *
 * [detail] carries a short human-readable reason for a non-online state. It is built
 * from the mapped [com.amarhelper.console.core.result.AppError], never from a raw
 * response body, so a service that echoes credentials cannot leak them into the UI.
 */
data class ServiceHealth(
    val service: ServiceId,
    val state: HealthState,
    val latencyMillis: Long? = null,
    val lastCheckedEpochMillis: Long? = null,
    val version: String? = null,
    val detail: String? = null,
) {
    companion object {
        fun notConfigured(service: ServiceId) = ServiceHealth(
            service = service,
            state = HealthState.NOT_CONFIGURED,
            detail = "No URL set",
        )
    }
}

/**
 * Health of a dependency that the app cannot and must not probe directly
 * (PostgreSQL, Redis). These are reported by the gateway's health endpoint.
 */
data class DependencyHealth(
    val name: String,
    val state: HealthState,
    val detail: String? = null,
)
