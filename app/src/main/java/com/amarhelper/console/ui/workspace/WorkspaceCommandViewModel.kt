package com.amarhelper.console.ui.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amarhelper.console.core.result.ApiResult
import com.amarhelper.console.data.config.ConfigStore
import com.amarhelper.console.domain.model.AgentProvider
import com.amarhelper.console.domain.model.RealtimeUpdate
import com.amarhelper.console.domain.model.TaskState
import com.amarhelper.console.domain.model.TaskSubmission
import com.amarhelper.console.domain.repository.AgentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WorkspaceCommandUiState(
    val conversationId: String? = null,
    val statusLabel: String? = null,
    val isWorking: Boolean = false,
    val isBusy: Boolean = false,
    val input: String = "",
    val notice: String? = null,
) {
    val suggestions: List<WorkspaceCommand> get() = WorkspaceCommand.matching(input)

    /** Interrupt is offered only when there is something to interrupt. */
    val canInterrupt: Boolean get() = conversationId != null && isWorking && !isBusy
}

/**
 * Drives the native command bar that sits under the embedded workspace.
 *
 * The bar deliberately talks to the same repository the native screens use rather than
 * to the page: the OpenHands web app is upstream code that can change shape at any
 * release, whereas `/api/conversations/{id}/stop` and the Socket.IO event stream are the
 * contracts this app already tests against.
 */
@HiltViewModel
class WorkspaceCommandViewModel @Inject constructor(
    private val agentRepository: AgentRepository,
    private val configStore: ConfigStore,
) : ViewModel() {

    private val _state = MutableStateFlow(WorkspaceCommandUiState())
    val state: StateFlow<WorkspaceCommandUiState> = _state.asStateFlow()

    private val effects = Channel<WorkspaceEffect>(Channel.BUFFERED)
    val effect: Flow<WorkspaceEffect> = effects.receiveAsFlow()

    private var statusJob: Job? = null

    /** Called whenever the WebView navigates, including client-side route changes. */
    fun onUrlChanged(url: String?) {
        val conversationId = WorkspaceUrl.conversationId(url)
        if (conversationId == _state.value.conversationId) return

        _state.update { it.copy(conversationId = conversationId, statusLabel = null, isWorking = false) }
        statusJob?.cancel()
        if (conversationId == null) return

        // Status comes from the same realtime stream the native console uses. Only
        // status frames are consumed here — the transcript belongs to the page.
        statusJob = viewModelScope.launch {
            agentRepository.liveEvents(AgentProvider.OPEN_HANDS, conversationId)
                .catch { _state.update { current -> current.copy(statusLabel = null, isWorking = false) } }
                .collect { update ->
                    when (update) {
                        is RealtimeUpdate.AgentStatus -> _state.update { current ->
                            current.copy(
                                statusLabel = update.label,
                                isWorking = update.state == TaskState.RUNNING || update.state == TaskState.QUEUED,
                            )
                        }
                        is RealtimeUpdate.Connected -> Unit
                        is RealtimeUpdate.Reconnecting -> _state.update { it.copy(statusLabel = "Reconnecting…") }
                        is RealtimeUpdate.Event -> Unit
                    }
                }
        }
    }

    fun onInputChanged(value: String) = _state.update { it.copy(input = value, notice = null) }

    fun onSuggestionPicked(command: WorkspaceCommand) {
        if (command.takesArgument) {
            _state.update { it.copy(input = "${command.token} ") }
        } else {
            _state.update { it.copy(input = "") }
            run(command, argument = null)
        }
    }

    /** Runs whatever is typed. Non-command text is refused rather than silently sent. */
    fun submit() {
        val input = _state.value.input
        val parsed = WorkspaceCommand.parse(input)
        if (parsed == null) {
            _state.update {
                it.copy(notice = "Type a / command, or use the page's own input box to talk to the agent.")
            }
            return
        }
        _state.update { it.copy(input = "") }
        run(parsed.command, parsed.argument)
    }

    fun interrupt() = run(WorkspaceCommand.STOP, argument = null)

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    private fun run(command: WorkspaceCommand, argument: String?) {
        val conversationId = _state.value.conversationId
        if (command.needsConversation && conversationId == null) {
            _state.update { it.copy(notice = "Open a conversation first.") }
            return
        }

        viewModelScope.launch {
            val baseUrl = configStore.current().openHandsUrl
            if (baseUrl.isBlank() && command != WorkspaceCommand.HELP) {
                _state.update { it.copy(notice = "Set the OpenHands URL in Settings first.") }
                return@launch
            }

            when (command) {
                WorkspaceCommand.STOP -> withBusy {
                    when (val result = agentRepository.cancel(AgentProvider.OPEN_HANDS, conversationId!!)) {
                        is ApiResult.Success -> _state.update {
                            it.copy(notice = "Interrupted.", isWorking = false)
                        }
                        is ApiResult.Failure -> _state.update { it.copy(notice = result.error.message) }
                    }
                }

                WorkspaceCommand.NEW -> withBusy {
                    val submission = TaskSubmission(
                        prompt = argument.orEmpty().ifBlank { "New conversation" },
                        provider = AgentProvider.OPEN_HANDS,
                    )
                    when (val result = agentRepository.submitTask(submission)) {
                        is ApiResult.Success -> effects.send(
                            WorkspaceEffect.Navigate(WorkspaceUrl.conversationUrl(baseUrl, result.data.id)),
                        )
                        is ApiResult.Failure -> _state.update { it.copy(notice = result.error.message) }
                    }
                }

                WorkspaceCommand.SESSIONS ->
                    effects.send(WorkspaceEffect.Navigate(WorkspaceUrl.sessionsUrl(baseUrl)))

                WorkspaceCommand.SETTINGS ->
                    effects.send(WorkspaceEffect.Navigate(WorkspaceUrl.settingsUrl(baseUrl)))

                WorkspaceCommand.RELOAD -> effects.send(WorkspaceEffect.Reload)

                WorkspaceCommand.CHROME -> {
                    val target = conversationId
                        ?.let { WorkspaceUrl.conversationUrl(baseUrl, it) }
                        ?: WorkspaceUrl.sessionsUrl(baseUrl)
                    effects.send(WorkspaceEffect.OpenExternally(target))
                }

                WorkspaceCommand.DEVTOOLS -> effects.send(WorkspaceEffect.OpenDevTools)

                WorkspaceCommand.DIAG -> effects.send(WorkspaceEffect.RunDiagnostics)

                WorkspaceCommand.HELP -> _state.update {
                    it.copy(notice = WorkspaceCommand.entries.joinToString("  ") { entry -> entry.token })
                }
            }
        }
    }

    private suspend fun withBusy(block: suspend () -> Unit) {
        _state.update { it.copy(isBusy = true) }
        try {
            block()
        } finally {
            _state.update { it.copy(isBusy = false) }
        }
    }

    override fun onCleared() {
        statusJob?.cancel()
        super.onCleared()
    }
}
