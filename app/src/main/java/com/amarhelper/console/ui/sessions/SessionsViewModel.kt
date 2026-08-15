package com.amarhelper.console.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amarhelper.console.core.result.ApiResult
import com.amarhelper.console.core.result.AppError
import com.amarhelper.console.domain.model.AgentProvider
import com.amarhelper.console.domain.model.AgentSession
import com.amarhelper.console.domain.repository.AgentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SessionsUiState(
    val isLoading: Boolean = true,
    val sessions: List<AgentSession> = emptyList(),
    val error: AppError? = null,
    val notice: String? = null,
) {
    val active: List<AgentSession> get() = sessions.filter { it.state.isActive }
    val previous: List<AgentSession> get() = sessions.filterNot { it.state.isActive }
}

@HiltViewModel
class SessionsViewModel @Inject constructor(
    private val agentRepository: AgentRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SessionsUiState())
    val state: StateFlow<SessionsUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            when (val result = agentRepository.listSessions()) {
                is ApiResult.Success -> _state.update {
                    it.copy(isLoading = false, sessions = result.data, error = null)
                }
                is ApiResult.Failure -> _state.update {
                    it.copy(isLoading = false, error = result.error)
                }
            }
        }
    }

    /** Deletion is offered only where the provider documents it; otherwise the user is told. */
    fun delete(provider: AgentProvider, sessionId: String) {
        viewModelScope.launch {
            when (val result = agentRepository.deleteSession(provider, sessionId)) {
                is ApiResult.Success -> _state.update { current ->
                    current.copy(sessions = current.sessions.filterNot { it.id == sessionId && it.provider == provider })
                }
                is ApiResult.Failure -> _state.update { it.copy(notice = result.error.message) }
            }
        }
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }
}
