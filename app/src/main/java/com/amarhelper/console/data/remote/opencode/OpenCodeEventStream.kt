package com.amarhelper.console.data.remote.opencode

import com.amarhelper.console.core.log.AppLogger
import com.amarhelper.console.data.config.ServiceId
import com.amarhelper.console.data.net.AuthInterceptor
import com.amarhelper.console.data.security.SecureCredentialStore
import com.amarhelper.console.domain.model.ConsoleEvent
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Consumes OpenCode's server-sent event stream (`GET /event`).
 *
 * Retrofit cannot express SSE, so this uses OkHttp directly with the read timeout
 * disabled. The flow cancels its underlying call when the collector goes away, so
 * leaving the console screen closes the socket rather than leaking it.
 */
@Singleton
class OpenCodeEventStream @Inject constructor(
    private val credentialStore: SecureCredentialStore,
    private val json: Json,
) {
    /**
     * Emits console events for [sessionId]. Events for other sessions are dropped so a
     * busy server does not flood one session's console.
     */
    fun events(baseUrl: String, sessionId: String): Flow<ConsoleEvent> = flow {
        val client = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // a stream never "finishes reading"
            .addInterceptor(AuthInterceptor(ServiceId.OPEN_CODE, credentialStore))
            .build()

        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/event")
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .build()

        val call = client.newCall(request)
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw java.io.IOException("Event stream rejected with HTTP ${response.code}")
                }
                val source = response.body?.source() ?: throw java.io.IOException("Empty event stream")
                var sequence = 0L
                while (currentCoroutineContext().isActive && !source.exhausted()) {
                    val line = source.readUtf8LineStrict()
                    if (!line.startsWith(DATA_PREFIX)) continue
                    val payload = line.removePrefix(DATA_PREFIX).trim()
                    if (payload.isEmpty()) continue
                    parse(payload, sessionId, sequence)?.let {
                        sequence++
                        emit(it)
                    }
                }
            }
        } finally {
            call.cancel()
        }
    }.flowOn(Dispatchers.IO)

    private fun parse(payload: String, sessionId: String, sequence: Long): ConsoleEvent? {
        return try {
            val root = json.parseToJsonElement(payload).jsonObject
            val type = root["type"]?.jsonPrimitive?.contentOrNullSafe() ?: return null
            val properties = root["properties"]?.jsonObject
            if (!belongsTo(properties, sessionId)) return null

            when (type) {
                "message.part.updated" -> textPart(properties, sequence)
                "session.error" -> ConsoleEvent(
                    id = "err-$sequence",
                    kind = ConsoleEvent.Kind.ERROR,
                    text = "The agent reported an error.",
                    timestampEpochMillis = System.currentTimeMillis(),
                )
                "session.idle" -> ConsoleEvent(
                    id = "idle-$sequence",
                    kind = ConsoleEvent.Kind.SYSTEM,
                    text = "Agent finished this turn.",
                    timestampEpochMillis = System.currentTimeMillis(),
                )
                else -> null
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Unparseable event frame dropped", e)
            null
        }
    }

    private fun belongsTo(properties: JsonObject?, sessionId: String): Boolean {
        val id = properties?.get("sessionID")?.jsonPrimitive?.contentOrNullSafe()
            ?: properties?.get("part")?.jsonObject?.get("sessionID")?.jsonPrimitive?.contentOrNullSafe()
        // Frames without a session id (e.g. server.connected) are global; keep them out
        // of a session console rather than guessing they belong to it.
        return id == sessionId
    }

    private fun textPart(properties: JsonObject?, sequence: Long): ConsoleEvent? {
        val part = properties?.get("part")?.jsonObject ?: return null
        val partType = part["type"]?.jsonPrimitive?.contentOrNullSafe()
        val id = part["id"]?.jsonPrimitive?.contentOrNullSafe() ?: "part-$sequence"
        return when (partType) {
            "text" -> part["text"]?.jsonPrimitive?.contentOrNullSafe()
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    ConsoleEvent(id, ConsoleEvent.Kind.AGENT, it, System.currentTimeMillis())
                }
            "tool" -> {
                val tool = part["tool"]?.jsonPrimitive?.contentOrNullSafe() ?: "tool"
                ConsoleEvent(
                    id = id,
                    kind = ConsoleEvent.Kind.TOOL,
                    text = "$tool()",
                    timestampEpochMillis = System.currentTimeMillis(),
                    toolName = tool,
                )
            }
            else -> null
        }
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
        runCatching { content }.getOrNull()

    private companion object {
        const val TAG = "OpenCodeEvents"
        const val DATA_PREFIX = "data:"
        const val CONNECT_TIMEOUT_SECONDS = 10L
    }
}
