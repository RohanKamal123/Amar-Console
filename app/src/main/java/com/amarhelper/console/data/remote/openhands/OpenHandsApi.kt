package com.amarhelper.console.data.remote.openhands

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * OpenHands **self-hosted OSS** REST API (`ghcr.io/all-hands-ai/openhands`).
 *
 * Routes and payloads are taken from the server source — `openhands/server/routes/
 * manage_conversations.py` (router prefix `/api`), `conversation.py` (prefix
 * `/api/conversations/{conversation_id}`) and `public.py` (prefix `/api/options`) —
 * not from the Cloud/Enterprise v1 documentation, which describes a different product.
 *
 * The distinction matters in a way that is easy to miss: the OSS server serves its
 * single-page frontend from a catch-all route, so a wrong path returns **HTTP 200 with
 * an HTML body** rather than a 404. A client built against the wrong contract therefore
 * fails as "malformed response" instead of "no such endpoint".
 */
interface OpenHandsApi {

    /**
     * Unauthenticated in OSS, and the cheapest call that proves this is really an
     * OpenHands server rather than something else on that port — it returns JSON, so an
     * HTML SPA shell served by the catch-all is caught immediately.
     */
    @GET("api/options/config")
    suspend fun optionsConfig(): Response<OptionsConfigDto>

    @GET("api/conversations")
    suspend fun conversations(
        @Query("limit") limit: Int = 20,
        @Query("page_id") pageId: String? = null,
        @Query("selected_repository") selectedRepository: String? = null,
    ): Response<ConversationPageDto>

    @GET("api/conversations/{id}")
    suspend fun conversation(@Path("id") id: String): Response<ConversationDto>

    @POST("api/conversations")
    suspend fun createConversation(
        @Body body: InitSessionRequest,
    ): Response<ConversationResponseDto>

    @DELETE("api/conversations/{id}")
    suspend fun deleteConversation(@Path("id") id: String): Response<Unit>

    @POST("api/conversations/{id}/stop")
    suspend fun stopConversation(@Path("id") id: String): Response<Unit>

    @POST("api/conversations/{id}/start")
    suspend fun startConversation(@Path("id") id: String): Response<Unit>

    /**
     * The conversation transcript. `limit` is capped at 100 by the server — a larger
     * value is rejected with 400 rather than clamped.
     */
    @GET("api/conversations/{id}/events")
    suspend fun events(
        @Path("id") id: String,
        @Query("start_id") startId: Int = 0,
        @Query("limit") limit: Int = 100,
        @Query("reverse") reverse: Boolean = false,
    ): Response<EventPageDto>

    @POST("api/conversations/{id}/message")
    suspend fun addMessage(
        @Path("id") id: String,
        @Body body: AddMessageRequest,
    ): Response<Unit>
}
