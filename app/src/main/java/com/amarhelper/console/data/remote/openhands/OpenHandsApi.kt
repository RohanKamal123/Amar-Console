package com.amarhelper.console.data.remote.openhands

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * OpenHands Sandbox / Cloud REST API, v1.
 *
 * Endpoints and payload shapes follow the published contract at
 * https://docs.openhands.dev/openhands/usage/api/v1 and
 * https://docs.openhands.dev/openhands/usage/cloud/cloud-api — no route here is invented.
 *
 * Capabilities the published contract does NOT define (sending a follow-up message to a
 * running conversation, per-event streaming, cancellation) are reported as
 * [com.amarhelper.console.core.result.AppError.Unsupported] rather than faked. See
 * ARCHITECTURE.md, "Known contract gaps".
 */
interface OpenHandsApi {

    @POST("api/v1/app-conversations")
    suspend fun createConversation(
        @Body body: CreateConversationRequest,
    ): Response<StartConversationResponse>

    @GET("api/v1/app-conversations")
    suspend fun conversationsByIds(
        @Query("ids") ids: String,
    ): Response<List<ConversationDto>>

    @GET("api/v1/app-conversations/search")
    suspend fun searchConversations(
        @Query("limit") limit: Int = 20,
    ): Response<ConversationPageDto>

    @GET("api/v1/app-conversations/start-tasks")
    suspend fun startTasks(
        @Query("ids") ids: String,
    ): Response<List<StartTaskDto>>
}
