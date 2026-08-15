package com.amarhelper.console.data.remote.litellm

import retrofit2.Response
import retrofit2.http.GET

/**
 * LiteLLM proxy health endpoints, per https://docs.litellm.ai/docs/proxy/health.
 *
 * `/health/liveliness` and `/health/readiness` are unauthenticated probes; `/health`
 * (which tests every configured model) requires a key and is deliberately not called
 * from the phone — it is an expensive fan-out to every provider.
 */
interface LiteLlmApi {

    @GET("health/liveliness")
    suspend fun liveliness(): Response<Unit>

    @GET("health/readiness")
    suspend fun readiness(): Response<LiteLlmReadinessDto>
}
