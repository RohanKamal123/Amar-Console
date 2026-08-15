package com.amarhelper.console.data.remote.openhands

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CreateConversationRequest(
    @SerialName("initial_message") val initialMessage: InitialMessage,
    @SerialName("selected_repository") val selectedRepository: String? = null,
)

@Serializable
data class InitialMessage(
    val content: List<MessageContent>,
)

@Serializable
data class MessageContent(
    val type: String = "text",
    val text: String,
)

@Serializable
data class StartConversationResponse(
    val id: String? = null,
    val status: String? = null,
    @SerialName("app_conversation_id") val appConversationId: String? = null,
    @SerialName("sandbox_id") val sandboxId: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class ConversationDto(
    val id: String,
    val title: String? = null,
    @SerialName("sandbox_status") val sandboxStatus: String? = null,
    @SerialName("execution_status") val executionStatus: String? = null,
    @SerialName("selected_repository") val selectedRepository: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class ConversationPageDto(
    val items: List<ConversationDto> = emptyList(),
    @SerialName("next_page_id") val nextPageId: String? = null,
)

@Serializable
data class StartTaskDto(
    val id: String,
    val status: String? = null,
    @SerialName("app_conversation_id") val appConversationId: String? = null,
)
