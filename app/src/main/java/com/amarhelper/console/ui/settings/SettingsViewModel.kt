package com.amarhelper.console.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amarhelper.console.data.config.AppConfig
import com.amarhelper.console.data.config.ConfigStore
import com.amarhelper.console.data.config.Environment
import com.amarhelper.console.data.config.ServiceId
import com.amarhelper.console.data.config.ThemeMode
import com.amarhelper.console.data.config.UrlValidation
import com.amarhelper.console.data.config.UrlValidator
import com.amarhelper.console.data.net.ApiClientFactory
import com.amarhelper.console.data.security.SecureCredentialStore
import com.amarhelper.console.domain.model.HealthState
import com.amarhelper.console.domain.repository.ServiceHealthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Result of the manual "Test connection" action for one service. */
sealed interface ConnectionTest {
    data object Idle : ConnectionTest
    data object Running : ConnectionTest
    data class Passed(val latencyMillis: Long?, val version: String?) : ConnectionTest
    data class Failed(val reason: String) : ConnectionTest
}

data class SettingsUiState(
    val config: AppConfig = AppConfig(),
    val urlDrafts: Map<ServiceId, String> = emptyMap(),
    val urlErrors: Map<ServiceId, String> = emptyMap(),
    val urlWarnings: Map<ServiceId, String> = emptyMap(),
    val credentialSet: Map<ServiceId, Boolean> = emptyMap(),
    val credentialUpdatedAt: Map<ServiceId, Long?> = emptyMap(),
    val tests: Map<ServiceId, ConnectionTest> = emptyMap(),
    val notice: String? = null,
)

/**
 * Settings is the only place configuration is entered, and the only place credentials
 * are written. Stored secrets are never read back into the UI — the screen shows
 * "Saved · 2h ago" and nothing else.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val configStore: ConfigStore,
    private val credentialStore: SecureCredentialStore,
    private val healthRepository: ServiceHealthRepository,
    private val clientFactory: ApiClientFactory,
) : ViewModel() {

    private val local = MutableStateFlow(SettingsUiState())

    private val credentialPresence = combine(
        credentialStore.presence(ServiceId.IDE),
        credentialStore.presence(ServiceId.OPEN_HANDS),
        credentialStore.presence(ServiceId.OPEN_CODE),
        credentialStore.presence(ServiceId.LITE_LLM),
        credentialStore.presence(ServiceId.GATEWAY),
    ) { ide, openHands, openCode, liteLlm, gateway ->
        mapOf(
            ServiceId.IDE to ide,
            ServiceId.OPEN_HANDS to openHands,
            ServiceId.OPEN_CODE to openCode,
            ServiceId.LITE_LLM to liteLlm,
            ServiceId.GATEWAY to gateway,
        )
    }

    /**
     * Persisted configuration, credential presence and transient screen state (drafts,
     * validation messages, test results) merged into one immutable snapshot.
     */
    val state: StateFlow<SettingsUiState> = combine(
        configStore.config,
        credentialPresence,
        local,
    ) { config, presence, screen ->
        screen.copy(
            config = config,
            urlDrafts = ServiceId.entries.associateWith { service ->
                screen.urlDrafts[service] ?: config.urlFor(service)
            },
            credentialSet = presence.mapValues { it.value.isSet },
            credentialUpdatedAt = presence.mapValues { it.value.updatedAtEpochMillis },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())


    fun onUrlChanged(service: ServiceId, value: String) {
        local.update { current ->
            current.copy(
                urlDrafts = current.urlDrafts + (service to value),
                urlErrors = current.urlErrors - service,
                urlWarnings = current.urlWarnings - service,
                tests = current.tests + (service to ConnectionTest.Idle),
            )
        }
    }

    fun saveUrl(service: ServiceId) {
        val draft = state.value.urlDrafts[service].orEmpty()
        if (draft.isBlank()) {
            viewModelScope.launch {
                configStore.setUrl(service, "")
                clientFactory.invalidate()
                local.update { it.copy(notice = "${service.displayName} URL cleared.") }
            }
            return
        }
        when (val validation = UrlValidator.validate(draft)) {
            is UrlValidation.Invalid -> local.update {
                it.copy(urlErrors = it.urlErrors + (service to validation.reason))
            }
            is UrlValidation.Valid -> viewModelScope.launch {
                configStore.setUrl(service, validation.normalized)
                clientFactory.invalidate()
                local.update { current ->
                    current.copy(
                        urlDrafts = current.urlDrafts + (service to validation.normalized),
                        urlErrors = current.urlErrors - service,
                        urlWarnings = validation.warning
                            ?.let { current.urlWarnings + (service to it) }
                            ?: (current.urlWarnings - service),
                        notice = "${service.displayName} URL saved.",
                    )
                }
            }
        }
    }

    fun saveToken(service: ServiceId, token: String) {
        viewModelScope.launch {
            credentialStore.setToken(service, token)
            clientFactory.invalidate()
            local.update {
                it.copy(notice = if (token.isBlank()) "${service.displayName} credential cleared." else "${service.displayName} credential saved.")
            }
        }
    }

    fun clearToken(service: ServiceId) {
        viewModelScope.launch {
            credentialStore.clearToken(service)
            clientFactory.invalidate()
            local.update { it.copy(notice = "${service.displayName} credential cleared.") }
        }
    }

    /** Forget every stored secret — the app's logout. */
    fun clearAllCredentials() {
        viewModelScope.launch {
            credentialStore.clearAll()
            clientFactory.invalidate()
            local.update { it.copy(notice = "All stored credentials removed.") }
        }
    }

    fun testConnection(service: ServiceId) {
        local.update { it.copy(tests = it.tests + (service to ConnectionTest.Running)) }
        viewModelScope.launch {
            val health = healthRepository.checkAll().firstOrNull { it.service == service }
            val result = when (health?.state) {
                HealthState.ONLINE -> ConnectionTest.Passed(health.latencyMillis, health.version)
                HealthState.DEGRADED -> ConnectionTest.Failed(health.detail ?: "Reachable, but the check did not pass.")
                HealthState.NOT_CONFIGURED -> ConnectionTest.Failed("Set a URL first.")
                else -> ConnectionTest.Failed(health?.detail ?: "No response.")
            }
            local.update { it.copy(tests = it.tests + (service to result)) }
        }
    }

    fun setEnvironment(environment: Environment) {
        viewModelScope.launch { configStore.setEnvironment(environment) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { configStore.setThemeMode(mode) }
    }

    fun setLiteLlmHealthPath(path: String) {
        viewModelScope.launch {
            configStore.setLiteLlmHealthPath(path)
            clientFactory.invalidate()
            local.update { it.copy(notice = "LiteLLM health path saved.") }
        }
    }

    fun setPollInterval(seconds: Int) {
        viewModelScope.launch { configStore.setPollInterval(seconds) }
    }

    fun setClaudeStyleWorkspaces(enabled: Boolean) {
        viewModelScope.launch { configStore.setClaudeStyleWorkspaces(enabled) }
    }

    fun setOpenWorkspacesInApp(enabled: Boolean) {
        viewModelScope.launch { configStore.setOpenWorkspacesInApp(enabled) }
    }

    fun setVerboseLogging(enabled: Boolean) {
        viewModelScope.launch { configStore.setVerboseNetworkLogging(enabled) }
    }

    fun dismissNotice() = local.update { it.copy(notice = null) }
}
