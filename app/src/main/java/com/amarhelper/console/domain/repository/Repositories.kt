package com.amarhelper.console.domain.repository

import com.amarhelper.console.core.result.ApiResult
import com.amarhelper.console.domain.model.AgentProvider
import com.amarhelper.console.domain.model.AgentSession
import com.amarhelper.console.domain.model.ConsoleEvent
import com.amarhelper.console.domain.model.DependencyHealth
import com.amarhelper.console.domain.model.ServiceHealth
import com.amarhelper.console.domain.model.RealtimeUpdate
import com.amarhelper.console.domain.model.TaskSubmission
import kotlinx.coroutines.flow.Flow

/** Agent conversations across every configured provider. */
interface AgentRepository {

    suspend fun submitTask(submission: TaskSubmission): ApiResult<AgentSession>

    suspend fun listSessions(): ApiResult<List<AgentSession>>

    suspend fun session(provider: AgentProvider, id: String): ApiResult<AgentSession>

    /** Replays what the agent has already produced. */
    suspend fun history(provider: AgentProvider, sessionId: String): ApiResult<List<ConsoleEvent>>

    /** Live transcript, connection and agent-status updates. */
    fun liveEvents(provider: AgentProvider, sessionId: String): Flow<RealtimeUpdate>

    suspend fun sendMessage(provider: AgentProvider, sessionId: String, message: String): ApiResult<Unit>

    fun supportsStreaming(provider: AgentProvider): Boolean

    suspend fun cancel(provider: AgentProvider, sessionId: String): ApiResult<Unit>

    suspend fun deleteSession(provider: AgentProvider, sessionId: String): ApiResult<Unit>

    /** Providers that currently have a URL configured. */
    suspend fun availableProviders(): List<AgentProvider>
}

/** Reachability of the configured infrastructure. */
interface ServiceHealthRepository {

    suspend fun checkAll(): List<ServiceHealth>

    /** PostgreSQL/Redis as reported by the gateway; empty when no gateway is configured. */
    suspend fun dependencies(): List<DependencyHealth>
}
