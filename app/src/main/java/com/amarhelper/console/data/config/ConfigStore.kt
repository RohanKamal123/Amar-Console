package com.amarhelper.console.data.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.configDataStore: DataStore<Preferences> by preferencesDataStore(name = "console_config")

/**
 * Persists [AppConfig]. Non-secret values only — tokens live in [com.amarhelper.console.data.security.SecureCredentialStore].
 */
@Singleton
class ConfigStore @Inject constructor(
    private val context: Context,
) {
    private object Keys {
        val ENVIRONMENT = stringPreferencesKey("environment")
        val OPEN_HANDS_URL = stringPreferencesKey("open_hands_url")
        val OPEN_CODE_URL = stringPreferencesKey("open_code_url")
        val LITE_LLM_URL = stringPreferencesKey("lite_llm_url")
        val GATEWAY_URL = stringPreferencesKey("gateway_url")
        val POLL_INTERVAL = intPreferencesKey("poll_interval_seconds")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val VERBOSE_LOGGING = booleanPreferencesKey("verbose_network_logging")
    }

    val config: Flow<AppConfig> = context.configDataStore.data
        .catch { cause ->
            // A corrupt preferences file must not crash startup; fall back to defaults.
            if (cause is IOException) emit(emptyPreferences()) else throw cause
        }
        .map { prefs ->
            AppConfig(
                environment = prefs[Keys.ENVIRONMENT]?.let { runCatching { Environment.valueOf(it) }.getOrNull() }
                    ?: Environment.PRODUCTION,
                openHandsUrl = prefs[Keys.OPEN_HANDS_URL].orEmpty(),
                openCodeUrl = prefs[Keys.OPEN_CODE_URL].orEmpty(),
                liteLlmUrl = prefs[Keys.LITE_LLM_URL].orEmpty(),
                gatewayUrl = prefs[Keys.GATEWAY_URL].orEmpty(),
                pollIntervalSeconds = prefs[Keys.POLL_INTERVAL] ?: AppConfig.DEFAULT_POLL_SECONDS,
                themeMode = prefs[Keys.THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                    ?: ThemeMode.SYSTEM,
                verboseNetworkLogging = prefs[Keys.VERBOSE_LOGGING] ?: false,
            )
        }

    suspend fun current(): AppConfig = config.first()

    suspend fun setUrl(service: ServiceId, url: String) {
        val key = when (service) {
            ServiceId.OPEN_HANDS -> Keys.OPEN_HANDS_URL
            ServiceId.OPEN_CODE -> Keys.OPEN_CODE_URL
            ServiceId.LITE_LLM -> Keys.LITE_LLM_URL
            ServiceId.GATEWAY -> Keys.GATEWAY_URL
        }
        context.configDataStore.edit { it[key] = url.trim().trimEnd('/') }
    }

    suspend fun setEnvironment(environment: Environment) {
        context.configDataStore.edit { it[Keys.ENVIRONMENT] = environment.name }
    }

    suspend fun setPollInterval(seconds: Int) {
        val clamped = seconds.coerceIn(AppConfig.MIN_POLL_SECONDS, AppConfig.MAX_POLL_SECONDS)
        context.configDataStore.edit { it[Keys.POLL_INTERVAL] = clamped }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.configDataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setVerboseNetworkLogging(enabled: Boolean) {
        context.configDataStore.edit { it[Keys.VERBOSE_LOGGING] = enabled }
    }

    suspend fun clear() {
        context.configDataStore.edit { it.clear() }
    }
}
