package com.amarhelper.console.ui.services

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amarhelper.console.domain.model.DependencyHealth
import com.amarhelper.console.domain.model.ServiceHealth
import com.amarhelper.console.domain.repository.ServiceHealthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ServicesUiState(
    val isLoading: Boolean = true,
    val services: List<ServiceHealth> = emptyList(),
    val dependencies: List<DependencyHealth> = emptyList(),
    val gatewayConfigured: Boolean = false,
)

@HiltViewModel
class ServicesViewModel @Inject constructor(
    private val repository: ServiceHealthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ServicesUiState())
    val state: StateFlow<ServicesUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            val servicesDeferred = async { repository.checkAll() }
            val dependenciesDeferred = async { repository.dependencies() }
            val services = servicesDeferred.await()
            val dependencies = dependenciesDeferred.await()
            _state.update {
                it.copy(
                    isLoading = false,
                    services = services,
                    dependencies = dependencies,
                    gatewayConfigured = services.any {
                        health ->
                        health.service == com.amarhelper.console.data.config.ServiceId.GATEWAY &&
                            health.state != com.amarhelper.console.domain.model.HealthState.NOT_CONFIGURED
                    },
                )
            }
        }
    }
}
