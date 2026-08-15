package com.amarhelper.console.data.remote.gateway

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.GET

/**
 * The optional aggregating gateway in front of the stack.
 *
 * IMPORTANT: unlike the OpenHands, OpenCode and LiteLLM interfaces, this is not a
 * published third-party contract — it is the contract *your* gateway must implement
 * for the app to display PostgreSQL and Redis health. The app never connects to a
 * database directly; a phone has no business holding database credentials.
 *
 * Expected shape of `GET /health`:
 * ```json
 * { "status": "ok",
 *   "version": "1.4.0",
 *   "dependencies": { "postgres": "up", "redis": "degraded" } }
 * ```
 * If the endpoint is absent the Services screen shows "not reported by gateway"
 * instead of inventing a status. See ARCHITECTURE.md.
 */
interface GatewayApi {

    @GET("health")
    suspend fun health(): Response<GatewayHealthDto>
}

@Serializable
data class GatewayHealthDto(
    val status: String? = null,
    val version: String? = null,
    val dependencies: Map<String, String> = emptyMap(),
)
