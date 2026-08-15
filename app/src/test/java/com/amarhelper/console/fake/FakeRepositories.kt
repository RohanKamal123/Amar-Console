package com.amarhelper.console.fake

import com.amarhelper.console.core.result.ApiResult
import com.amarhelper.console.core.result.AppError
import com.amarhelper.console.data.config.ServiceId
import com.amarhelper.console.domain.model.AgentProvider
import com.amarhelper.console.domain.model.AgentSession
import com.amarhelper.console.domain.model.ConsoleEvent
import com.amarhelper.console.domain.model.DependencyHealth
import com.amarhelper.console.domain.model.HealthState
import com.amarhelper.console.domain.model.ServiceHealth
import com.amarhelper.console.domain.model.RealtimeUpdate
import com.amarhelper.console.domain.model.TaskState
import com.amarhelper.console.domain.model.TaskSubmission
import com.amarhelper.console.domain.repository.AgentRepository
import com.amarhelper.console.domain.repository.ServiceHealthRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow

class FakeAgentRepository : AgentRepository {

    var providers: List<AgentProvider> = listOf(AgentProvider.OPEN_CODE, AgentProvider.OPEN_HANDS)
    var submitResult: ApiResult<AgentSession> = ApiResult.Success(session("ses_1"))
    var sessionsResult: ApiResult<List<AgentSession>> = ApiResult.Success(listOf(session("ses_1")))
    var sessionResult: ApiResult<AgentSession> = ApiResult.Success(session("ses_1"))
    var historyResult: ApiResult<List<ConsoleEvent>> = ApiResult.Success(emptyList())
    var cancelResult: ApiResult<Unit> = ApiResult.Failure(AppError.Unsupported("Cancelling a running OpenCode task"))
    var deleteResult: ApiResult<Unit> = ApiResult.Success(Unit)
    var sendMessageResult: ApiResult<Unit> = ApiResult.Success(Unit)
    var streaming: Boolean = true

    val liveEvents = MutableSharedFlow<RealtimeUpdate>(extraBufferCapacity = 64)
    var streamFailure: Throwable? = null

    var submittedTasks = mutableListOf<TaskSubmission>()
    var deletedSessions = mutableListOf<String>()

    override suspend fun submitTask(submission: TaskSubmission): ApiResult<AgentSession> {
        submittedTasks += submission
        return submitResult
    }

    override suspend fun listSessions(): ApiResult<List<AgentSession>> = sessionsResult

    override suspend fun session(provider: AgentProvider, id: String): ApiResult<AgentSession> = sessionResult

    override suspend fun history(provider: AgentProvider, sessionId: String): ApiResult<List<ConsoleEvent>> =
        historyResult

    override fun liveEvents(provider: AgentProvider, sessionId: String): Flow<RealtimeUpdate> =
        streamFailure?.let { failure -> flow<RealtimeUpdate> { throw failure } } ?: liveEvents

    override suspend fun sendMessage(provider: AgentProvider, sessionId: String, message: String): ApiResult<Unit> =
        sendMessageResult

    override fun supportsStreaming(provider: AgentProvider): Boolean = streaming

    override suspend fun cancel(provider: AgentProvider, sessionId: String): ApiResult<Unit> = cancelResult

    override suspend fun deleteSession(provider: AgentProvider, sessionId: String): ApiResult<Unit> {
        deletedSessions += sessionId
        return deleteResult
    }

    override suspend fun availableProviders(): List<AgentProvider> = providers

    companion object {
        fun session(
            id: String,
            state: TaskState = TaskState.RUNNING,
            provider: AgentProvider = AgentProvider.OPEN_CODE,
            title: String = "Build a REST API for user authentication",
        ) = AgentSession(
            id = id,
            provider = provider,
            title = title,
            state = state,
            createdAtEpochMillis = 1_736_937_000_000L,
            lastActivityEpochMillis = 1_736_937_100_000L,
        )
    }
}

class FakeServiceHealthRepository : ServiceHealthRepository {

    var health: List<ServiceHealth> = listOf(
        ServiceHealth(ServiceId.OPEN_CODE, HealthState.ONLINE, latencyMillis = 42, version = "0.4.11"),
        ServiceHealth.notConfigured(ServiceId.OPEN_HANDS),
        ServiceHealth.notConfigured(ServiceId.LITE_LLM),
        ServiceHealth.notConfigured(ServiceId.GATEWAY),
    )
    var dependencies: List<DependencyHealth> = emptyList()

    override suspend fun checkAll(): List<ServiceHealth> = health

    override suspend fun dependencies(): List<DependencyHealth> = dependencies
}
