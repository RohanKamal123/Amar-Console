package com.amarhelper.console.data.remote.openhands

import com.amarhelper.console.core.log.AppLogger
import com.amarhelper.console.data.config.ServiceId
import com.amarhelper.console.data.security.SecureCredentialStore
import com.amarhelper.console.domain.model.RealtimeUpdate
import io.socket.client.IO
import io.socket.client.Socket
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.json.JSONObject

@Singleton
class OpenHandsRealtimeClient @Inject constructor(
    private val json: Json,
    private val credentialStore: SecureCredentialStore,
) {
    private val sockets = ConcurrentHashMap<String, Socket>()

    suspend fun updates(
        baseUrl: String,
        conversation: ConversationDto,
        latestEventId: String?,
    ): Flow<RealtimeUpdate> {
        val token = credentialStore.tokenFor(ServiceId.OPEN_HANDS)
        return socketFlow(baseUrl, conversation, latestEventId, token)
    }

    fun sendMessage(conversationId: String, message: String): Boolean {
        val socket = sockets[conversationId]?.takeIf(Socket::connected) ?: return false
        socket.emit(
            USER_ACTION,
            JSONObject().put("action", "message").put(
                "args",
                JSONObject().put("content", message),
            ),
        )
        return true
    }

    private fun socketFlow(
        baseUrl: String,
        conversation: ConversationDto,
        latestEventId: String?,
        token: String?,
    ): Flow<RealtimeUpdate> = callbackFlow {
        val query = buildList {
            add("conversation_id=${encode(conversation.conversationId)}")
            add("latest_event_id=${encode(latestEventId ?: "-1")}")
            add("providers_set=${encode(conversation.gitProvider.orEmpty())}")
            conversation.sessionApiKey?.let { add("session_api_key=${encode(it)}") }
        }.joinToString("&")
        val headers = token?.let { mapOf("Authorization" to listOf("Bearer $it")) }
        val options = IO.Options.builder()
            .setForceNew(true)
            .setMultiplex(false)
            .setPath(socketPath(conversation.url))
            .setQuery(query)
            .setTransports(arrayOf("websocket"))
            .setReconnection(true)
            .setReconnectionAttempts(Int.MAX_VALUE)
            .setReconnectionDelay(1_000)
            .setReconnectionDelayMax(10_000)
            .setExtraHeaders(headers)
            .build()
        val socket = IO.socket(URI.create(socketBaseUrl(baseUrl, conversation.url)), options)
        sockets[conversation.conversationId]?.disconnect()
        sockets[conversation.conversationId] = socket

        socket.on(Socket.EVENT_CONNECT) { trySend(RealtimeUpdate.Connected) }
        socket.io().on("reconnect_attempt") { trySend(RealtimeUpdate.Reconnecting) }
        socket.on(Socket.EVENT_DISCONNECT) { trySend(RealtimeUpdate.Reconnecting) }
        socket.on(Socket.EVENT_CONNECT_ERROR) { args ->
            AppLogger.w(TAG, "OpenHands realtime connection failed: ${args.firstOrNull()?.javaClass?.simpleName}")
            trySend(RealtimeUpdate.Reconnecting)
        }
        socket.on(OH_EVENT) { args ->
            val raw = args.firstOrNull() ?: return@on
            runCatching { json.parseToJsonElement(raw.toString()).jsonObject }
                .onSuccess { event ->
                    when (val mapped = OpenHandsEventMapper.map(event)) {
                        is OpenHandsEventMapper.Result.Transcript -> trySend(RealtimeUpdate.Event(mapped.event))
                        is OpenHandsEventMapper.Result.Status -> trySend(RealtimeUpdate.AgentStatus(mapped.label, mapped.state))
                        OpenHandsEventMapper.Result.Ignore -> Unit
                    }
                }
                .onFailure { AppLogger.w(TAG, "Dropped malformed OpenHands event", it) }
        }
        socket.connect()

        awaitClose {
            sockets.remove(conversation.conversationId, socket)
            socket.off()
            socket.disconnect()
        }
    }

    private fun socketBaseUrl(configured: String, conversationUrl: String?): String {
        if (conversationUrl.isNullOrBlank() || conversationUrl.startsWith('/')) return configured.trimEnd('/')
        val uri = URI.create(conversationUrl)
        return "${uri.scheme}://${uri.authority}"
    }

    private fun socketPath(conversationUrl: String?): String {
        if (conversationUrl.isNullOrBlank() || conversationUrl.startsWith('/')) return "/socket.io"
        val prefix = URI.create(conversationUrl).path.substringBefore("/api/conversations").trimEnd('/')
        return "$prefix/socket.io".ifBlank { "/socket.io" }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private companion object {
        const val TAG = "OpenHandsRealtime"
        const val OH_EVENT = "oh_event"
        const val USER_ACTION = "oh_user_action"
    }
}
