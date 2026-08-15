package com.amarhelper.console.data.net

import com.amarhelper.console.core.log.AppLogger
import java.io.IOException
import java.io.InterruptedIOException
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Retries with exponential backoff — but only when it is safe to do so.
 *
 * A request is retried only if its method is idempotent (GET/HEAD/PUT/DELETE) or it
 * explicitly opts in via the [IDEMPOTENT_HEADER]. POSTs that create conversations or
 * submit prompts are never replayed: a duplicate task is worse than a visible error.
 *
 * Retried conditions: transport IOException, 502, 503, 504, and 429 (honouring
 * Retry-After up to a cap). 500 is not retried automatically — it usually means the
 * request itself was rejected, and the user can retry explicitly.
 *
 * This sits on top of OkHttp's own connection-level recovery (address failover, stale
 * pooled connections), which is left enabled. That recovery only ever repeats a request
 * the server never answered; deciding whether an *answered* request may be repeated is
 * this interceptor's job.
 */
class RetryInterceptor(
    private val maxAttempts: Int = 3,
    private val initialBackoffMillis: Long = 500L,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val retryable = request.method in IDEMPOTENT_METHODS ||
            request.header(IDEMPOTENT_HEADER)?.equals("true", ignoreCase = true) == true

        var attempt = 0
        var lastFailure: IOException? = null

        while (attempt < maxAttempts) {
            if (chain.call().isCanceled()) throw IOException("Canceled")
            try {
                val response = chain.proceed(request)
                if (!retryable || !shouldRetry(response.code) || attempt == maxAttempts - 1) {
                    return response
                }
                val wait = retryAfterMillis(response) ?: backoff(attempt)
                response.close()
                AppLogger.d(TAG, "HTTP ${response.code} on ${request.method}; retrying in ${wait}ms")
                sleepOrThrow(wait)
            } catch (e: InterruptedIOException) {
                // A timeout or an interrupted call: retry only if the request is safe.
                if (!retryable || attempt == maxAttempts - 1) throw e
                lastFailure = e
                sleepOrThrow(backoff(attempt))
            } catch (e: IOException) {
                if (!retryable || attempt == maxAttempts - 1) throw e
                lastFailure = e
                sleepOrThrow(backoff(attempt))
            }
            attempt++
        }
        throw lastFailure ?: IOException("Request failed after $maxAttempts attempts")
    }

    private fun shouldRetry(code: Int): Boolean = code in RETRYABLE_STATUS

    private fun backoff(attempt: Int): Long = initialBackoffMillis shl attempt

    private fun retryAfterMillis(response: Response): Long? =
        response.header("Retry-After")?.toLongOrNull()
            ?.coerceAtMost(MAX_RETRY_AFTER_SECONDS)
            ?.times(1_000L)

    private fun sleepOrThrow(millis: Long) {
        try {
            sleeper(millis)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while backing off", e)
        }
    }

    companion object {
        const val IDEMPOTENT_HEADER = "X-Retry-Safe"
        private const val TAG = "RetryInterceptor"
        private const val MAX_RETRY_AFTER_SECONDS = 30L
        private val IDEMPOTENT_METHODS = setOf("GET", "HEAD", "PUT", "DELETE")
        private val RETRYABLE_STATUS = setOf(429, 502, 503, 504)
    }
}
