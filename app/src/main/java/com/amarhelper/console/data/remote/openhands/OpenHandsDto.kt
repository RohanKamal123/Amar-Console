package com.amarhelper.console.data.remote.openhands

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** `GET /api/options/config`. `APP_MODE` is `oss` on a self-hosted deployment. */
@Serializable
data class OptionsConfigDto(
    @SerialName("APP_MODE") val appMode: String? = null,
    @SerialName("FEATURE_FLAGS") val featureFlags: JsonObject? = null,
)

/** Body of `POST /api/conversations` — the server's `InitSessionRequest`. */
@Serializable
data class InitSessionRequest(
    @SerialName("initial_user_msg") val initialUserMsg: String? = null,
    val repository: String? = null,
    @SerialName("git_provider") val gitProvider: String? = null,
    @SerialName("selected_branch") val selectedBranch: String? = null,
    @SerialName("conversation_instructions") val conversationInstructions: String? = null,
)

/** Response of `POST /api/conversations` — the server's `ConversationResponse`. */
@Serializable
data class ConversationResponseDto(
    val status: String? = null,
    @SerialName("conversation_id") val conversationId: String? = null,
    val message: String? = null,
    @SerialName("conversation_status") val conversationStatus: String? = null,
)

/** `GET /api/conversations` — the server's `ConversationInfoResultSet`. */
@Serializable
data class ConversationPageDto(
    val results: List<ConversationDto> = emptyList(),
    @SerialName("next_page_id") val nextPageId: String? = null,
)

/**
 * The server's `ConversationInfo`. Every field the server sends is represented, so a
 * response is never silently truncated to the handful the UI happens to render today.
 */
@Serializable
data class ConversationDto(
    @SerialName("conversation_id") val conversationId: String,
    val title: String? = null,
    @SerialName("last_updated_at") val lastUpdatedAt: String? = null,
    /** `STARTING` · `RUNNING` · `STOPPED` · `ARCHIVED`. */
    val status: String? = null,
    /** e.g. `STATUS$READY`, `STATUS$BUILDING_RUNTIME`, `STATUS$ERROR_LLM_...`. */
    @SerialName("runtime_status") val runtimeStatus: String? = null,
    @SerialName("selected_repository") val selectedRepository: String? = null,
    @SerialName("selected_branch") val selectedBranch: String? = null,
    @SerialName("git_provider") val gitProvider: String? = null,
    val trigger: String? = null,
    @SerialName("num_connections") val numConnections: Int = 0,
    val url: String? = null,
    /**
     * The server may return a per-conversation API key here. It is deliberately never
     * stored, logged or displayed — see SECURITY.md.
     */
    @SerialName("session_api_key") val sessionApiKey: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("pr_number") val prNumber: List<Int> = emptyList(),
    @SerialName("conversation_version") val conversationVersion: String? = null,
)

/** `GET /api/conversations/{id}/events`. */
@Serializable
data class EventPageDto(
    val events: List<JsonObject> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
)

/** Body of `POST /api/conversations/{id}/message` — the server's `AddMessageRequest`. */
@Serializable
data class AddMessageRequest(val message: String)
