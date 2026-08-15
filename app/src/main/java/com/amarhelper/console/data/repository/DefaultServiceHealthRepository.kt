package com.amarhelper.console.data.repository

import com.amarhelper.console.core.result.ApiResult
import com.amarhelper.console.core.result.AppError
import com.amarhelper.console.data.config.ConfigStore
import com.amarhelper.console.data.config.ServiceId
import com.amarhelper.console.data.net.ApiClientFactory
import com.amarhelper.console.data.net.safeResponseCall
import com.amarhelper.console.data.remote.gateway.GatewayApi
import com.amarhelper.console.data.remote.litellm.LiteLlmApi
import com.amarhelper.console.data.remote.litellm.LiteLlmHealth
import com.amarhelper.console.data.remote.opencode.OpenCodeApi
import com.amarhelper.console.data.remote.openhands.OpenHandsApi
import com.amarhelper.console.data.remote.web.WebUiApi
import com.amarhelper.console.domain.model.DependencyHealth
import com.amarhelper.console.domain.model.HealthState
import com.amarhelper.console.domain.model.ServiceHealth
import com.amarhelper.console.domain.repository.ServiceHealthRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Probes each configured service and reports what came back.
 *
 * Probes run concurrently, so one unreachable host does not delay the rest of the
 * screen by its full timeout. A 401 is reported as DEGRADED ("reachable, credential
 * rejected") rather than OFFLINE — the distinction matters when debugging a VPS.
 */
@Singleton
class DefaultServiceHealthRepository @Inject constructor(
    private val configStore: ConfigStore,
    private val clientFactory: ApiClientFactory,
) : ServiceHealthRepository {

    override suspend fun checkAll(): List<ServiceHealth> = coroutineScope {
        val config = configStore.current()
        ServiceId.entries.map { service ->
            async {
                if (!config.isConfigured(service)) {
                    ServiceHealth.notConfigured(service)
                } else {
                    probe(service, config.urlFor(service))
                }
            }
        }.map { it.await() }
    }

    override suspend fun dependencies(): List<DependencyHealth> {
        val url = configStore.current().gatewayUrl
        if (url.isBlank()) return emptyList()
        val api = clientFactory.create(ServiceId.GATEWAY, url, GatewayApi::class.java)
        return when (val result = safeResponseCall { api.health() }) {
            is ApiResult.Failure -> emptyList()
            is ApiResult.Success -> result.data.dependencies.map { (name, state) ->
                DependencyHealth(
                    name = name.replaceFirstChar { it.uppercase() },
                    state = when (state.lowercase()) {
                        "up", "ok", "healthy", "connected" -> HealthState.ONLINE
                        "degraded", "slow", "warning" -> HealthState.DEGRADED
                        "down", "error", "unhealthy", "disconnected" -> HealthState.OFFLINE
                        else -> HealthState.UNKNOWN
                    },
                    detail = state,
                )
            }
        }
    }

    private suspend fun probe(service: ServiceId, url: String): ServiceHealth {
        val startedAt = System.nanoTime()
        val outcome: ApiResult<String?> = when (service) {
            ServiceId.IDE -> {
                val api = clientFactory.create(service, url, WebUiApi::class.java)
                when (val r = safeResponseCall { api.root() }) {
                    is ApiResult.Success -> ApiResult.Success("web workspace")
                    is ApiResult.Failure -> r
                }
            }
            ServiceId.OPEN_CODE -> {
                val api = clientFactory.create(service, url, OpenCodeApi::class.java)
                when (val r = safeResponseCall { api.health() }) {
                    is ApiResult.Success -> ApiResult.Success(r.data.version)
                    is ApiResult.Failure -> r
                }
            }
            ServiceId.LITE_LLM -> {
                val api = clientFactory.create(service, url, LiteLlmApi::class.java)
                val path = configStore.current().liteLlmHealthPath
                when (val r = safeResponseCall { api.probe("$url/$path") }) {
                    is ApiResult.Success -> ApiResult.Success(LiteLlmHealth.version(r.data))
                    is ApiResult.Failure -> r
                }
            }
            ServiceId.GATEWAY -> {
                val api = clientFactory.create(service, url, GatewayApi::class.java)
                when (val r = safeResponseCall { api.health() }) {
                    is ApiResult.Success -> ApiResult.Success(r.data.version)
                    is ApiResult.Failure -> r
                }
            }
            ServiceId.OPEN_HANDS -> {
                // /api/options/config is unauthenticated on a self-hosted server and
                // returns JSON, so a wrong host answering with the frontend's HTML shell
                // is caught here rather than surfacing later as a parse failure.
                val api = clientFactory.create(service, url, OpenHandsApi::class.java)
                when (val r = safeResponseCall { api.optionsConfig() }) {
                    is ApiResult.Success -> ApiResult.Success(r.data.appMode?.let { "mode: $it" })
                    is ApiResult.Failure -> r
                }
            }
        }
        val latency = (System.nanoTime() - startedAt) / 1_000_000
        val now = System.currentTimeMillis()

        return when (outcome) {
            is ApiResult.Success -> ServiceHealth(
                service = service,
                state = HealthState.ONLINE,
                latencyMillis = latency,
                lastCheckedEpochMillis = now,
                version = outcome.data,
            )
            is ApiResult.Failure -> ServiceHealth(
                service = service,
                state = outcome.error.toHealthState(),
                latencyMillis = latency.takeIf { outcome.error !is AppError.Offline },
                lastCheckedEpochMillis = now,
                detail = outcome.error.message,
            )
        }
    }

    private fun AppError.toHealthState(): HealthState = when (this) {
        is AppError.Unauthorized, is AppError.Forbidden -> HealthState.DEGRADED
        is AppError.NotFound -> HealthState.DEGRADED
        is AppError.RateLimited -> HealthState.DEGRADED
        is AppError.Malformed -> HealthState.DEGRADED
        is AppError.Offline, is AppError.Timeout -> HealthState.OFFLINE
        is AppError.ServerError -> HealthState.OFFLINE
        else -> HealthState.UNKNOWN
    }
}
