package com.amarhelper.console.ui.splash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amarhelper.console.data.config.ConfigStore
import com.amarhelper.console.domain.model.HealthState
import com.amarhelper.console.domain.repository.ServiceHealthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface SplashUiState {
    data object Initializing : SplashUiState

    /** Nothing is configured yet — send the user straight to Settings. */
    data object NeedsSetup : SplashUiState

    data class Ready(val onlineCount: Int, val totalConfigured: Int) : SplashUiState

    /**
     * Configuration exists but nothing answered. The user can retry or continue anyway —
     * the app must never trap someone on a splash screen because a VPS is down.
     */
    data class Unreachable(val reason: String) : SplashUiState
}

@HiltViewModel
class SplashViewModel @Inject constructor(
    private val configStore: ConfigStore,
    private val healthRepository: ServiceHealthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<SplashUiState>(SplashUiState.Initializing)
    val state: StateFlow<SplashUiState> = _state.asStateFlow()

    init {
        initialize()
    }

    fun initialize() {
        _state.value = SplashUiState.Initializing
        viewModelScope.launch {
            val config = configStore.current()
            if (!config.hasAnyService) {
                _state.value = SplashUiState.NeedsSetup
                return@launch
            }
            val health = healthRepository.checkAll()
            val configured = health.filter { it.state != HealthState.NOT_CONFIGURED }
            val reachable = configured.count { it.state == HealthState.ONLINE || it.state == HealthState.DEGRADED }
            _state.value = if (reachable == 0) {
                SplashUiState.Unreachable(
                    configured.firstNotNullOfOrNull { it.detail }
                        ?: "No configured service responded.",
                )
            } else {
                SplashUiState.Ready(onlineCount = reachable, totalConfigured = configured.size)
            }
        }
    }
}
