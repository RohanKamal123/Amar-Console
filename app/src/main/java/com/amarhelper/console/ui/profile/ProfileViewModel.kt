package com.amarhelper.console.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amarhelper.console.core.result.ApiResult
import com.amarhelper.console.core.result.AppError
import com.amarhelper.console.data.config.ConfigStore
import com.amarhelper.console.data.config.ServiceId
import com.amarhelper.console.data.net.ApiClientFactory
import com.amarhelper.console.data.net.safeResponseCall
import com.amarhelper.console.data.remote.litellm.LiteLlmApi
import com.amarhelper.console.data.remote.litellm.UsageSummaryDto
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProfileUiState(
    val loading: Boolean = true,
    val summary: UsageSummaryDto? = null,
    val error: AppError? = null,
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val configStore: ConfigStore,
    private val clientFactory: ApiClientFactory,
) : ViewModel() {
    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val config = configStore.current()
            if (config.liteLlmUrl.isBlank()) {
                _state.value = ProfileUiState(
                    loading = false,
                    error = AppError.NotConfigured("LiteLLM"),
                )
                return@launch
            }
            val api = clientFactory.create(ServiceId.LITE_LLM, config.liteLlmUrl, LiteLlmApi::class.java)
            when (val result = safeResponseCall { api.usageSummary(30) }) {
                is ApiResult.Success -> _state.value = ProfileUiState(loading = false, summary = result.data)
                is ApiResult.Failure -> _state.value = ProfileUiState(loading = false, error = result.error)
            }
        }
    }
}
