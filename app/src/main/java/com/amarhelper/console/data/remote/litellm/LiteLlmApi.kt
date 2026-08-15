package com.amarhelper.console.data.remote.litellm

import kotlinx.serialization.json.JsonObject
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Url

/**
 * LiteLLM health probe.
 *
 * The upstream proxy serves `/health/readiness` and `/health/liveliness`
 * (docs.litellm.ai/docs/proxy/health), but deployments frequently put a custom router
 * behind the same name — one was found serving this stack — and a custom app need not
 * implement either route. The probe path is therefore configuration, not a constant:
 * point it at whatever the deployment actually exposes.
 *
 * The response is read as a generic object because the shape differs between the real
 * proxy (`{"status": "healthy", "db": ...}`) and any stand-in. Reachability is decided
 * by the HTTP status; the body is only mined for optional detail.
 */
interface LiteLlmApi {

    @GET
    suspend fun probe(@Url url: String): Response<JsonObject>
}
