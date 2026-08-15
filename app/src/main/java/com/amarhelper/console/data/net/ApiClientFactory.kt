package com.amarhelper.console.data.net

import com.amarhelper.console.BuildConfig
import com.amarhelper.console.data.config.ServiceId
import com.amarhelper.console.data.security.SecureCredentialStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Builds a Retrofit client per (service, base URL).
 *
 * Base URLs are runtime configuration, so clients cannot be created once at startup;
 * they are built on demand and cached, and the cache is dropped when the user edits a
 * URL. Each service gets a client carrying only its own credential.
 */
@Singleton
class ApiClientFactory @Inject constructor(
    private val credentialStore: SecureCredentialStore,
    private val json: Json,
) {
    private val clients = ConcurrentHashMap<String, Retrofit>()

    @Volatile
    var verboseLogging: Boolean = false

    fun <T> create(service: ServiceId, baseUrl: String, api: Class<T>): T {
        val normalized = baseUrl.trimEnd('/') + "/"
        val retrofit = clients.getOrPut("${service.name}|$normalized") {
            Retrofit.Builder()
                .baseUrl(normalized)
                .client(okHttpClient(service))
                .addConverterFactory(json.asConverterFactory(JSON_MEDIA_TYPE))
                .build()
        }
        return retrofit.create(api)
    }

    /** Called when configuration changes so stale base URLs are not reused. */
    fun invalidate() = clients.clear()

    private fun okHttpClient(service: ServiceId): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        // OkHttp's connection-level recovery stays ON. It is what fails over between a
        // host's addresses (a tailnet name resolves to both IPv6 and IPv4) and discards
        // stale pooled connections after a VPN reconnect. It never replays a request the
        // server already answered — that is [RetryInterceptor]'s job, and it refuses to
        // replay a POST, so a task can still never be submitted twice.
        .retryOnConnectionFailure(true)
        .addInterceptor(RetryInterceptor())
        .addInterceptor(AuthInterceptor(service, credentialStore))
        .apply { loggingInterceptor()?.let(::addInterceptor) }
        .build()

    /**
     * Body logging exists only in debug builds, and even then Authorization is redacted
     * before anything reaches logcat.
     */
    private fun loggingInterceptor(): HttpLoggingInterceptor? {
        if (!BuildConfig.VERBOSE_LOGGING) return null
        return HttpLoggingInterceptor().apply {
            level = if (verboseLogging) HttpLoggingInterceptor.Level.BODY else HttpLoggingInterceptor.Level.BASIC
            redactHeader("Authorization")
            redactHeader("Proxy-Authorization")
            redactHeader("Cookie")
            redactHeader("Set-Cookie")
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        const val CONNECT_TIMEOUT_SECONDS = 10L
        const val READ_TIMEOUT_SECONDS = 30L
        const val WRITE_TIMEOUT_SECONDS = 30L
        const val CALL_TIMEOUT_SECONDS = 60L
    }
}
