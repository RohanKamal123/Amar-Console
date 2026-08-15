package com.amarhelper.console.data.remote.opencode

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * OpenCode server API (`opencode serve`, default port 4096).
 *
 * Routes follow the published reference at https://opencode.ai/docs/server.
 * The live event stream (`GET /event`, server-sent events) is consumed by
 * [OpenCodeEventStream] rather than Retrofit, since Retrofit has no SSE support.
 */
interface OpenCodeApi {

    @GET("global/health")
    suspend fun health(): Response<OpenCodeHealthDto>

    @POST("session")
    suspend fun createSession(@Body body: CreateSessionRequest): Response<SessionDto>

    @GET("session")
    suspend fun listSessions(): Response<List<SessionDto>>

    @GET("session/{id}")
    suspend fun session(@Path("id") id: String): Response<SessionDto>

    @DELETE("session/{id}")
    suspend fun deleteSession(@Path("id") id: String): Response<Unit>

    @POST("session/{id}/prompt_async")
    suspend fun sendPromptAsync(
        @Path("id") id: String,
        @Body body: SendMessageRequest,
    ): Response<Unit>

    @GET("session/{id}/message")
    suspend fun messages(
        @Path("id") id: String,
        @Query("limit") limit: Int = 200,
    ): Response<List<MessageEnvelopeDto>>
}
