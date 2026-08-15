package com.amarhelper.console.ui.console

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amarhelper.console.core.log.AppLogger
import com.amarhelper.console.core.result.ApiResult
import com.amarhelper.console.core.result.AppError
import com.amarhelper.console.data.config.ConfigStore
import com.amarhelper.console.data.net.ErrorMapper
import com.amarhelper.console.domain.model.AgentProvider
import com.amarhelper.console.domain.model.AgentSession
import com.amarhelper.console.domain.model.ConsoleEvent
import com.amarhelper.console.domain.model.TaskState
import com.amarhelper.console.domain.model.RealtimeUpdate
import com.amarhelper.console.domain.repository.AgentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** How the console is currently receiving output. */
enum class ConnectionState { CONNECTING, LIVE, POLLING, DISCONNECTED, NO_STREAM }

data class ConsoleUiState(
    val session: AgentSession? = null,
    val events: List<ConsoleEvent> = emptyList(),
    val connection: ConnectionState = ConnectionState.CONNECTING,
    val isLoadingSession: Boolean = true,
    val error: AppError? = null,
    val notice: String? = null,
    val droppedLines: Int = 0,
    val agentStatus: String? = null,
    val messageDraft: String = "",
    val isSending: Boolean = false,
) {
    val state: TaskState get() = session?.state ?: TaskState.UNKNOWN
}

/**
 * Drives one agent session.
 *
 * Both providers publish live events: OpenCode over SSE and self-hosted OpenHands over
 * Socket.IO. OpenHands state bookkeeping is kept in [ConsoleUiState.agentStatus] rather
 * than appended to the visible transcript.
 *
 * The event buffer is bounded: past [MAX_EVENTS] lines the oldest are dropped and the
 * count is surfaced in the UI, so a runaway agent cannot exhaust memory.
 */
@HiltViewModel
class SessionConsoleViewModel @Inject constructor(
    private val agentRepository: AgentRepository,
    private val configStore: ConfigStore,
) : ViewModel() {

    private val _state = MutableStateFlow(ConsoleUiState())
    val state: StateFlow<ConsoleUiState> = _state.asStateFlow()

    private var provider: AgentProvider? = null
    private var sessionId: String = ""
    private var streamJob: Job? = null
    private var pollJob: Job? = null

    fun start(provider: AgentProvider, sessionId: String) {
        if (this.provider == provider && this.sessionId == sessionId && streamJob != null) return
        this.provider = provider
        this.sessionId = sessionId
        loadSession()
    }

    fun loadSession() {
        _state.update { it.copy(isLoadingSession = true, error = null) }
        val provider = provider ?: return
        viewModelScope.launch {
            when (val result = agentRepository.session(provider, sessionId)) {
                is ApiResult.Success -> {
                    _state.update { it.copy(session = result.data, isLoadingSession = false) }
                    loadHistory(provider)
                    if (agentRepository.supportsStreaming(provider)) startStream(provider) else startPolling(provider)
                }
                is ApiResult.Failure -> _state.update {
                    it.copy(isLoadingSession = false, error = result.error, connection = ConnectionState.DISCONNECTED)
                }
            }
        }
    }

    private suspend fun loadHistory(provider: AgentProvider) {
        when (val result = agentRepository.history(provider, sessionId)) {
            is ApiResult.Success -> _state.update { it.copy(events = result.data.takeLast(MAX_EVENTS)) }
            is ApiResult.Failure -> if (result.error is AppError.Unsupported) {
                _state.update { it.copy(notice = result.error.message) }
            } else {
                _state.update { it.copy(error = result.error) }
            }
        }
    }

    private fun startStream(provider: AgentProvider) {
        streamJob?.cancel()
        _state.update { it.copy(connection = ConnectionState.CONNECTING) }
        streamJob = viewModelScope.launch {
            agentRepository.liveEvents(provider, sessionId)
                .catch { cause ->
                    AppLogger.w(TAG, "Event stream ended", cause)
                    _state.update {
                        it.copy(
                            connection = ConnectionState.DISCONNECTED,
                            error = ErrorMapper.fromThrowable(cause),
                        )
                    }
                }
                .collect { update ->
                    when (update) {
                        RealtimeUpdate.Connected -> _state.update {
                            it.copy(connection = ConnectionState.LIVE, error = null)
                        }
                        RealtimeUpdate.Reconnecting -> _state.update {
                            it.copy(connection = ConnectionState.CONNECTING)
                        }
                        is RealtimeUpdate.AgentStatus -> _state.update { current ->
                            current.copy(
                                agentStatus = update.label,
                                session = update.state?.let { current.session?.copy(state = it) } ?: current.session,
                            )
                        }
                        is RealtimeUpdate.Event -> appendEvent(update.event)
                    }
                }
        }
    }

    private fun appendEvent(event: ConsoleEvent) = _state.update { current ->
        val causeId = event.tool?.causeId
        val actionIndex = causeId?.let { cause ->
            current.events.indexOfFirst { it.tool?.callId == cause }.takeIf { it >= 0 }
        }
        val appended = when {
            actionIndex != null -> current.events.toMutableList().apply {
                val action = this[actionIndex]
                val actionTool = action.tool!!
                this[actionIndex] = action.copy(
                    tool = actionTool.copy(
                        output = event.tool?.output ?: actionTool.output,
                        isDiff = event.tool?.isDiff == true || actionTool.isDiff,
                        succeeded = event.tool?.succeeded ?: actionTool.succeeded,
                    ),
                )
            }
            current.events.any { it.id == event.id } -> current.events.map { if (it.id == event.id) event else it }
            else -> current.events + event
        }
        val overflow = (appended.size - MAX_EVENTS).coerceAtLeast(0)
        current.copy(
            connection = ConnectionState.LIVE,
            error = null,
            events = if (overflow > 0) appended.drop(overflow) else appended,
            droppedLines = current.droppedLines + overflow,
        )
    }

    /** Status polling for providers with no stream. Stops as soon as the task is terminal. */
    private fun startPolling(provider: AgentProvider) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            val intervalMillis = configStore.current().pollIntervalSeconds * 1_000L
            _state.update { it.copy(connection = ConnectionState.POLLING) }
            while (isActive) {
                delay(intervalMillis)
                when (val result = agentRepository.session(provider, sessionId)) {
                    is ApiResult.Success -> {
                        _state.update { it.copy(session = result.data, error = null) }
                        if (result.data.state.isTerminal) {
                            _state.update { it.copy(connection = ConnectionState.NO_STREAM) }
                            return@launch
                        }
                    }
                    is ApiResult.Failure -> _state.update {
                        it.copy(connection = ConnectionState.DISCONNECTED, error = result.error)
                    }
                }
            }
        }
    }

    fun reconnect() {
        val provider = provider ?: return
        _state.update { it.copy(error = null) }
        if (agentRepository.supportsStreaming(provider)) startStream(provider) else startPolling(provider)
    }

    fun cancelTask() {
        val provider = provider ?: return
        viewModelScope.launch {
            when (val result = agentRepository.cancel(provider, sessionId)) {
                is ApiResult.Success -> loadSession()
                is ApiResult.Failure -> _state.update { it.copy(notice = result.error.message) }
            }
        }
    }

    fun updateMessageDraft(value: String) = _state.update { it.copy(messageDraft = value) }

    fun sendMessage() {
        val provider = provider ?: return
        val message = state.value.messageDraft.trim()
        if (message.isBlank() || state.value.isSending) return
        _state.update { it.copy(isSending = true) }
        viewModelScope.launch {
            when (val result = agentRepository.sendMessage(provider, sessionId, message)) {
                is ApiResult.Success -> _state.update { it.copy(messageDraft = "", isSending = false) }
                is ApiResult.Failure -> _state.update {
                    it.copy(isSending = false, notice = result.error.message)
                }
            }
        }
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    override fun onCleared() {
        streamJob?.cancel()
        pollJob?.cancel()
        super.onCleared()
    }

    private companion object {
        const val TAG = "SessionConsole"
        const val MAX_EVENTS = 1_500
    }
}
