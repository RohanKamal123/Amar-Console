package com.amarhelper.console.data.remote.opencode

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class OpenCodeHealthDto(
    val healthy: Boolean = false,
    val version: String? = null,
)

@Serializable
data class CreateSessionRequest(
    val title: String? = null,
    @SerialName("parentID") val parentId: String? = null,
)

@Serializable
data class SendMessageRequest(
    val parts: List<MessagePartDto>,
    val model: String? = null,
    val agent: String? = null,
    @SerialName("messageID") val messageId: String? = null,
)

@Serializable
data class MessagePartDto(
    val type: String = "text",
    val text: String? = null,
    val tool: String? = null,
    val state: JsonElement? = null,
)

@Serializable
data class SessionDto(
    val id: String,
    val title: String? = null,
    @SerialName("parentID") val parentId: String? = null,
    val time: SessionTimeDto? = null,
)

@Serializable
data class SessionTimeDto(
    val created: Long? = null,
    val updated: Long? = null,
)

/** `GET /session/:id/message` returns `{ info, parts }` envelopes. */
@Serializable
data class MessageEnvelopeDto(
    val info: MessageInfoDto? = null,
    val parts: List<MessagePartDto> = emptyList(),
)

@Serializable
data class MessageInfoDto(
    val id: String? = null,
    val role: String? = null,
    val time: MessageTimeDto? = null,
    val error: JsonElement? = null,
)

@Serializable
data class MessageTimeDto(
    val created: Long? = null,
    val completed: Long? = null,
)
