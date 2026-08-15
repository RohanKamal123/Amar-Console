package com.amarhelper.console.ui.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amarhelper.console.data.config.AppConfig
import com.amarhelper.console.data.config.ConfigStore
import com.amarhelper.console.data.config.ServiceId
import com.amarhelper.console.data.security.SecureCredentialStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class WorkspaceViewModel @Inject constructor(
    configStore: ConfigStore,
    private val credentialStore: SecureCredentialStore,
) : ViewModel() {
    val config: StateFlow<AppConfig> = configStore.config.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppConfig(),
    )

    suspend fun openCodePassword(): String? = credentialStore.tokenFor(ServiceId.OPEN_CODE)
}
