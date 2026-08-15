package com.amarhelper.console

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amarhelper.console.data.config.ConfigStore
import com.amarhelper.console.data.config.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Holds only the theme preference so the whole tree does not recompose on config edits. */
@HiltViewModel
class ThemeViewModel @Inject constructor(
    configStore: ConfigStore,
) : ViewModel() {

    val themeMode: StateFlow<ThemeMode> = configStore.config
        .map { it.themeMode }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.SYSTEM)
}
