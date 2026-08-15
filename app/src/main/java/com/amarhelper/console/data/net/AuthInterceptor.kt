package com.amarhelper.console.data.net

import com.amarhelper.console.data.config.ServiceId
import com.amarhelper.console.data.security.SecureCredentialStore
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Attaches the stored credential for exactly one service.
 *
 * Each service gets its own OkHttp client (see [ApiClientFactory]), so a token can
 * never be sent to a host it does not belong to. Requests carry the header only when
 * a credential exists; anonymous deployments work unchanged.
 *
 * OpenCode uses HTTP basic auth (OPENCODE_SERVER_PASSWORD); the other services use a
 * bearer token. The stored value is used verbatim as the credential — the app never
 * logs, displays, or persists it in plaintext.
 */
class AuthInterceptor(
    private val service: ServiceId,
    private val credentialStore: SecureCredentialStore,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header(HEADER_AUTHORIZATION) != null) return chain.proceed(request)

        // OkHttp interceptors are blocking by contract; this runs on OkHttp's own
        // dispatcher thread, never the main thread.
        val token = runBlocking { credentialStore.tokenFor(service) }
            ?: return chain.proceed(request)

        val authorized = request.newBuilder()
            .header(HEADER_AUTHORIZATION, "Bearer $token")
            .build()
        return chain.proceed(authorized)
    }

    private companion object {
        const val HEADER_AUTHORIZATION = "Authorization"
    }
}
