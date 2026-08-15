package com.amarhelper.console.data.repository

import com.amarhelper.console.core.result.ApiResult
import com.amarhelper.console.core.result.AppError
import com.amarhelper.console.data.config.ConfigStore
import com.amarhelper.console.data.config.ServiceId
import com.amarhelper.console.data.net.ApiClientFactory
import com.amarhelper.console.data.net.safeResponseCall
import com.amarhelper.console.data.remote.opencode.CreateSessionRequest
import com.amarhelper.console.data.remote.opencode.MessagePartDto
import com.amarhelper.console.data.remote.opencode.OpenCodeApi
import com.amarhelper.console.data.remote.opencode.OpenCodeEventStream
import com.amarhelper.console.data.remote.opencode.SendMessageRequest
import com.amarhelper.console.data.remote.openhands.InitSessionRequest
import com.amarhelper.console.data.remote.openhands.OpenHandsApi
import com.amarhelper.console.domain.model.AgentProvider
import com.amarhelper.console.domain.model.AgentSession
import com.amarhelper.console.domain.model.ConsoleEvent
import com.amarhelper.console.domain.model.TaskState
import com.amarhelper.console.domain.model.TaskSubmission
import com.amarhelper.console.domain.repository.AgentRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * Talks to whichever agent providers are configured.
 *
 * Every route used here comes from a published API reference. Where a provider's
 * documented contract has no endpoint for something the UI offers, this returns
 * [AppError.Unsupported] — it never fabricates a success.
 */
@Singleton
class DefaultAgentRepository @Inject constructor(
    private val configStore: ConfigStore,
    private val clientFactory: ApiClientFactory,
    private val eventStream: OpenCodeEventStream,
) : AgentRepository {

    private suspend fun openHands(): OpenHandsApi? {
        val url = configStore.current().openHandsUrl
        return if (url.isBlank()) null else clientFactory.create(ServiceId.OPEN_HANDS, url, OpenHandsApi::class.java)
    }

    private suspend fun openCode(): OpenCodeApi? {
        val url = configStore.current().openCodeUrl
        return if (url.isBlank()) null else clientFactory.create(ServiceId.OPEN_CODE, url, OpenCodeApi::class.java)
    }

    override suspend fun availableProviders(): List<AgentProvider> {
        val config = configStore.current()
        return buildList {
            if (config.openHandsUrl.isNotBlank()) add(AgentProvider.OPEN_HANDS)
            if (config.openCodeUrl.isNotBlank()) add(AgentProvider.OPEN_CODE)
        }
    }

    override suspend fun submitTask(submission: TaskSubmission): ApiResult<AgentSession> =
        when (submission.provider) {
            AgentProvider.OPEN_HANDS -> submitToOpenHands(submission)
            AgentProvider.OPEN_CODE -> submitToOpenCode(submission)
        }

    private suspend fun submitToOpenHands(submission: TaskSubmission): ApiResult<AgentSession> {
        val api = openHands() ?: return ApiResult.Failure(AppError.NotConfigured("OpenHands"))
        val request = InitSessionRequest(
            initialUserMsg = submission.prompt,
            repository = submission.repository?.takeIf { it.isNotBlank() },
        )
        return when (val result = safeResponseCall { api.createConversation(request) }) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> {
                val dto = result.data
                val id = dto.conversationId
                    ?: return ApiResult.Failure(AppError.Malformed("OpenHands did not return a conversation id."))
                ApiResult.Success(
                    AgentSession(
                        id = id,
                        provider = AgentProvider.OPEN_HANDS,
                        title = submission.prompt.toTitle(),
                        state = StatusMapping.fromOpenHands(dto.conversationStatus ?: dto.status, null),
                        createdAtEpochMillis = System.currentTimeMillis(),
                        lastActivityEpochMillis = System.currentTimeMillis(),
                        repository = submission.repository,
                    ),
                )
            }
        }
    }

    /**
     * OpenCode needs two calls: create the session, then post the prompt. The prompt is
     * sent with `prompt_async` so the request returns immediately and progress arrives
     * over the event stream instead of blocking on a long agent turn.
     */
    private suspend fun submitToOpenCode(submission: TaskSubmission): ApiResult<AgentSession> {
        val api = openCode() ?: return ApiResult.Failure(AppError.NotConfigured("OpenCode"))
        val created = safeResponseCall { api.createSession(CreateSessionRequest(title = submission.prompt.toTitle())) }
        val session = when (created) {
            is ApiResult.Failure -> return created
            is ApiResult.Success -> created.data
        }
        val sent = safeResponseCall {
            api.sendPromptAsync(session.id, SendMessageRequest(parts = listOf(MessagePartDto(text = submission.prompt))))
        }
        if (sent is ApiResult.Failure) return sent
        return ApiResult.Success(
            AgentSession(
                id = session.id,
                provider = AgentProvider.OPEN_CODE,
                title = session.title ?: submission.prompt.toTitle(),
                state = TaskState.RUNNING,
                createdAtEpochMillis = StatusMapping.normalizeEpoch(session.time?.created) ?: System.currentTimeMillis(),
                lastActivityEpochMillis = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun listSessions(): ApiResult<List<AgentSession>> {
        val sessions = mutableListOf<AgentSession>()
        var lastError: AppError? = null

        openHands()?.let { api ->
            when (val result = safeResponseCall { api.conversations(limit = SESSION_PAGE_SIZE) }) {
                is ApiResult.Success -> result.data.results.forEach { sessions += it.toDomain() }
                is ApiResult.Failure -> lastError = result.error
            }
        }
        openCode()?.let { api ->
            when (val result = safeResponseCall { api.listSessions() }) {
                is ApiResult.Success -> result.data.forEach { dto ->
                    sessions += AgentSession(
                        id = dto.id,
                        provider = AgentProvider.OPEN_CODE,
                        title = dto.title.orEmpty().ifBlank { "Untitled session" },
                        // OpenCode's session object carries no execution status; the console
                        // derives live state from the event stream.
                        state = TaskState.UNKNOWN,
                        createdAtEpochMillis = StatusMapping.normalizeEpoch(dto.time?.created),
                        lastActivityEpochMillis = StatusMapping.normalizeEpoch(dto.time?.updated),
                    )
                }
                is ApiResult.Failure -> lastError = result.error
            }
        }

        // Surface an error only when nothing at all could be listed; a single dead
        // provider must not blank out sessions from a healthy one.
        return when {
            sessions.isNotEmpty() -> ApiResult.Success(
                sessions.sortedByDescending { it.lastActivityEpochMillis ?: it.createdAtEpochMillis ?: 0L },
            )
            lastError != null -> ApiResult.Failure(lastError!!)
            else -> ApiResult.Success(emptyList())
        }
    }

    override suspend fun session(provider: AgentProvider, id: String): ApiResult<AgentSession> = when (provider) {
        AgentProvider.OPEN_HANDS -> {
            val api = openHands()
            if (api == null) {
                ApiResult.Failure(AppError.NotConfigured("OpenHands"))
            } else {
                when (val result = safeResponseCall { api.conversation(id) }) {
                    is ApiResult.Failure -> result
                    is ApiResult.Success -> ApiResult.Success(result.data.toDomain())
                }
            }
        }
        AgentProvider.OPEN_CODE -> {
            val api = openCode()
            if (api == null) {
                ApiResult.Failure(AppError.NotConfigured("OpenCode"))
            } else {
                when (val result = safeResponseCall { api.session(id) }) {
                    is ApiResult.Failure -> result
                    is ApiResult.Success -> ApiResult.Success(
                        AgentSession(
                            id = result.data.id,
                            provider = AgentProvider.OPEN_CODE,
                            title = result.data.title.orEmpty().ifBlank { "Untitled session" },
                            state = TaskState.UNKNOWN,
                            createdAtEpochMillis = StatusMapping.normalizeEpoch(result.data.time?.created),
                            lastActivityEpochMillis = StatusMapping.normalizeEpoch(result.data.time?.updated),
                        ),
                    )
                }
            }
        }
    }

    override suspend fun history(provider: AgentProvider, sessionId: String): ApiResult<List<ConsoleEvent>> =
        when (provider) {
            AgentProvider.OPEN_HANDS -> {
                val api = openHands()
                if (api == null) {
                    ApiResult.Failure(AppError.NotConfigured("OpenHands"))
                } else {
                    // The server caps `limit` at 100 and rejects anything larger with 400.
                    when (val result = safeResponseCall { api.events(sessionId, limit = EVENT_PAGE_SIZE) }) {
                        is ApiResult.Failure -> result
                        is ApiResult.Success -> ApiResult.Success(
                            result.data.events.mapNotNull { it.toConsoleEvent() },
                        )
                    }
                }
            }
            AgentProvider.OPEN_CODE -> {
                val api = openCode()
                if (api == null) {
                    ApiResult.Failure(AppError.NotConfigured("OpenCode"))
                } else {
                    when (val result = safeResponseCall { api.messages(sessionId) }) {
                        is ApiResult.Failure -> result
                        is ApiResult.Success -> ApiResult.Success(
                            result.data.flatMapIndexed { index, envelope ->
                                val kind = when (envelope.info?.role) {
                                    "user" -> ConsoleEvent.Kind.USER
                                    else -> ConsoleEvent.Kind.AGENT
                                }
                                envelope.parts.mapIndexedNotNull { partIndex, part ->
                                    val text = when (part.type) {
                                        "text" -> part.text
                                        "tool" -> part.tool?.let { "$it()" }
                                        else -> null
                                    }
                                    text?.takeIf { it.isNotBlank() }?.let {
                                        ConsoleEvent(
                                            id = "${envelope.info?.id ?: index}-$partIndex",
                                            kind = if (part.type == "tool") ConsoleEvent.Kind.TOOL else kind,
                                            text = it,
                                            timestampEpochMillis = StatusMapping.normalizeEpoch(envelope.info?.time?.created),
                                            toolName = part.tool,
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }

    override fun liveEvents(provider: AgentProvider, sessionId: String): Flow<ConsoleEvent> = when (provider) {
        AgentProvider.OPEN_CODE -> flow {
            val url = configStore.current().openCodeUrl
            if (url.isNotBlank()) emitAll(eventStream.events(url, sessionId))
        }
        AgentProvider.OPEN_HANDS -> emptyFlow()
    }

    override fun supportsStreaming(provider: AgentProvider): Boolean = provider == AgentProvider.OPEN_CODE

    override suspend fun cancel(provider: AgentProvider, sessionId: String): ApiResult<Unit> = when (provider) {
        AgentProvider.OPEN_HANDS -> {
            val api = openHands()
            if (api == null) {
                ApiResult.Failure(AppError.NotConfigured("OpenHands"))
            } else {
                when (val result = safeResponseCall { api.stopConversation(sessionId) }) {
                    is ApiResult.Failure -> result
                    is ApiResult.Success -> ApiResult.Success(Unit)
                }
            }
        }
        // OpenCode's published API has no abort route; deleting the session is the only
        // way to end a run, and that is offered separately.
        AgentProvider.OPEN_CODE -> ApiResult.Failure(AppError.Unsupported("Cancelling a running OpenCode task"))
    }

    override suspend fun deleteSession(provider: AgentProvider, sessionId: String): ApiResult<Unit> = when (provider) {
        AgentProvider.OPEN_CODE -> {
            val api = openCode()
            if (api == null) {
                ApiResult.Failure(AppError.NotConfigured("OpenCode"))
            } else {
                when (val result = safeResponseCall { api.deleteSession(sessionId) }) {
                    is ApiResult.Failure -> result
                    is ApiResult.Success -> ApiResult.Success(Unit)
                }
            }
        }
        AgentProvider.OPEN_HANDS -> {
            val api = openHands()
            if (api == null) {
                ApiResult.Failure(AppError.NotConfigured("OpenHands"))
            } else {
                when (val result = safeResponseCall { api.deleteConversation(sessionId) }) {
                    is ApiResult.Failure -> result
                    is ApiResult.Success -> ApiResult.Success(Unit)
                }
            }
        }
    }

    private fun com.amarhelper.console.data.remote.openhands.ConversationDto.toDomain() = AgentSession(
        id = conversationId,
        provider = AgentProvider.OPEN_HANDS,
        title = title.orEmpty().ifBlank { "Untitled conversation" },
        state = StatusMapping.fromOpenHands(status, runtimeStatus),
        createdAtEpochMillis = StatusMapping.parseIsoTimestamp(createdAt),
        lastActivityEpochMillis = StatusMapping.parseIsoTimestamp(lastUpdatedAt ?: createdAt),
        repository = selectedRepository,
        // session_api_key is deliberately not carried into the domain model.
        detail = runtimeStatus?.substringAfter('$')?.lowercase()?.replace('_', ' '),
    )

    /**
     * One OpenHands event → one console line.
     *
     * Events are heterogeneous (actions, observations, messages), so they arrive as raw
     * JSON objects and only the keys the server guarantees are read.
     */
    private fun kotlinx.serialization.json.JsonObject.toConsoleEvent(): ConsoleEvent? {
        fun prim(key: String) = this[key] as? kotlinx.serialization.json.JsonPrimitive
        fun str(key: String): String? = prim(key)?.takeIf { it.isString }?.content
        fun long(key: String): Long? = prim(key)?.content?.toLongOrNull()

        val source = str("source")
        val action = str("action")
        val observation = str("observation")
        val args = this["args"] as? kotlinx.serialization.json.JsonObject
        val message = str("message")?.takeIf { it.isNotBlank() }

        // agent_state_changed is bookkeeping, not conversation: it drives the status
        // indicator and is kept out of the transcript entirely.
        if (observation == "agent_state_changed" || action == "change_agent_state") {
            val state = args?.get("agent_state")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            return ConsoleEvent(
                id = "state-${long("id") ?: message.hashCode()}",
                kind = ConsoleEvent.Kind.SYSTEM,
                text = message.orEmpty(),
                timestampEpochMillis = StatusMapping.parseIsoTimestamp(str("timestamp")),
                eventId = long("id"),
                isStatusOnly = true,
                agentState = state,
            )
        }

        val command = args?.let { arguments ->
            listOf("command", "code", "content", "path", "query")
                .firstNotNullOfOrNull { key ->
                    (arguments[key] as? kotlinx.serialization.json.JsonPrimitive)
                        ?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
                }
        }

        val text = message ?: command ?: action?.let { "$it()" } ?: observation ?: return null

        val kind = when {
            observation == "error" -> ConsoleEvent.Kind.ERROR
            source == "user" -> ConsoleEvent.Kind.USER
            action != null && action != "message" -> ConsoleEvent.Kind.TOOL
            observation != null -> ConsoleEvent.Kind.TOOL
            source == "environment" -> ConsoleEvent.Kind.SYSTEM
            else -> ConsoleEvent.Kind.AGENT
        }

        return ConsoleEvent(
            id = long("id")?.toString() ?: text.hashCode().toString(),
            kind = kind,
            text = text,
            timestampEpochMillis = StatusMapping.parseIsoTimestamp(str("timestamp")),
            eventId = long("id"),
            causeId = long("cause"),
            toolName = action ?: observation,
            command = command,
        )
    }

    private fun String.toTitle(): String {
        val firstLine = trim().lineSequence().firstOrNull().orEmpty()
        return if (firstLine.length <= TITLE_LIMIT) firstLine else firstLine.take(TITLE_LIMIT - 1) + "…"
    }

    private companion object {
        const val SESSION_PAGE_SIZE = 30
        const val EVENT_PAGE_SIZE = 100
        const val TITLE_LIMIT = 60
    }
}
