package com.amarhelper.console.ui.task

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amarhelper.console.core.result.ApiResult
import com.amarhelper.console.core.result.AppError
import com.amarhelper.console.domain.model.AgentProvider
import com.amarhelper.console.domain.model.AgentSession
import com.amarhelper.console.domain.model.TaskSubmission
import com.amarhelper.console.domain.repository.AgentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NewTaskUiState(
    val prompt: String = "",
    val repository: String = "",
    val providers: List<AgentProvider> = emptyList(),
    val selectedProvider: AgentProvider? = null,
    val isLoadingProviders: Boolean = true,
    val isSubmitting: Boolean = false,
    val error: AppError? = null,
    val validationMessage: String? = null,
    val startedSession: AgentSession? = null,
) {
    val canSubmit: Boolean
        get() = !isSubmitting && selectedProvider != null && prompt.isNotBlank()
}

@HiltViewModel
class NewTaskViewModel @Inject constructor(
    private val agentRepository: AgentRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(NewTaskUiState())
    val state: StateFlow<NewTaskUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val providers = agentRepository.availableProviders()
            _state.update {
                it.copy(
                    providers = providers,
                    selectedProvider = providers.firstOrNull(),
                    isLoadingProviders = false,
                )
            }
        }
    }

    fun onPromptChange(value: String) {
        _state.update { it.copy(prompt = value.take(MAX_PROMPT_LENGTH), validationMessage = null) }
    }

    fun onRepositoryChange(value: String) {
        _state.update { it.copy(repository = value.trim().take(MAX_REPO_LENGTH)) }
    }

    fun onProviderSelected(provider: AgentProvider) {
        _state.update { it.copy(selectedProvider = provider, error = null) }
    }

    fun submit() {
        val current = _state.value
        val provider = current.selectedProvider ?: return
        val prompt = current.prompt.trim()

        if (prompt.length < MIN_PROMPT_LENGTH) {
            _state.update { it.copy(validationMessage = "Describe the task in at least $MIN_PROMPT_LENGTH characters.") }
            return
        }

        _state.update { it.copy(isSubmitting = true, error = null, validationMessage = null) }
        viewModelScope.launch {
            val submission = TaskSubmission(
                prompt = prompt,
                provider = provider,
                repository = current.repository.takeIf { it.isNotBlank() },
            )
            when (val result = agentRepository.submitTask(submission)) {
                is ApiResult.Success -> _state.update {
                    it.copy(isSubmitting = false, startedSession = result.data)
                }
                is ApiResult.Failure -> _state.update {
                    it.copy(isSubmitting = false, error = result.error)
                }
            }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    private companion object {
        const val MIN_PROMPT_LENGTH = 8
        const val MAX_PROMPT_LENGTH = 8_000
        const val MAX_REPO_LENGTH = 200
    }
}
