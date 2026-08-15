package com.amarhelper.console.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amarhelper.console.core.result.ApiResult
import com.amarhelper.console.core.result.AppError
import com.amarhelper.console.domain.model.AgentSession
import com.amarhelper.console.domain.model.HealthState
import com.amarhelper.console.domain.model.ServiceHealth
import com.amarhelper.console.domain.repository.AgentRepository
import com.amarhelper.console.domain.repository.ServiceHealthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DashboardUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val services: List<ServiceHealth> = emptyList(),
    val recentSessions: List<AgentSession> = emptyList(),
    val sessionsError: AppError? = null,
    val hasConfiguration: Boolean = true,
) {
    val activeSessions: List<AgentSession> get() = recentSessions.filter { it.state.isActive }
    val onlineServices: Int get() = services.count { it.state == HealthState.ONLINE }
    val configuredServices: Int get() = services.count { it.state != HealthState.NOT_CONFIGURED }
}

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val healthRepository: ServiceHealthRepository,
    private val agentRepository: AgentRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(DashboardUiState())
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    init {
        refresh(initial = true)
    }

    /**
     * One pass over health and sessions. Both run concurrently, and a failure in one
     * does not blank the other — a dead OpenCode must not hide OpenHands' sessions.
     */
    fun refresh(initial: Boolean = false) {
        _state.update { it.copy(isLoading = initial, isRefreshing = !initial) }
        viewModelScope.launch {
            val healthDeferred = async { healthRepository.checkAll() }
            val sessionsDeferred = async { agentRepository.listSessions() }

            val health = healthDeferred.await()
            val sessions = sessionsDeferred.await()

            _state.update { current ->
                current.copy(
                    isLoading = false,
                    isRefreshing = false,
                    services = health,
                    hasConfiguration = health.any { it.state != HealthState.NOT_CONFIGURED },
                    recentSessions = (sessions as? ApiResult.Success)?.data?.take(RECENT_LIMIT)
                        ?: current.recentSessions,
                    sessionsError = (sessions as? ApiResult.Failure)?.error,
                )
            }
        }
    }

    private companion object {
        const val RECENT_LIMIT = 5
    }
}
