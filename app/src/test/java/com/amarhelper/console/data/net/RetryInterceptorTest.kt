package com.amarhelper.console.data.net

import java.net.InetAddress
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The retry policy is a correctness concern, not a convenience: replaying a POST that
 * starts an agent task would run the task twice.
 */
class RetryInterceptorTest {

    private lateinit var server: MockWebServer
    private val slept = mutableListOf<Long>()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .retryOnConnectionFailure(true) // as in ApiClientFactory
            .callTimeout(5, TimeUnit.SECONDS)
            .addInterceptor(RetryInterceptor(maxAttempts = 3, initialBackoffMillis = 10) { slept += it })
            .build()
    }

    @Before
    fun setUp() {
        // Bound explicitly to 127.0.0.1: "localhost" can resolve to ::1 as well, and a
        // retry that picks the address the server is not listening on fails to connect.
        server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `a GET is retried until it succeeds`() {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val response = client.newCall(Request.Builder().url(server.url("/health")).build()).execute()

        assertEquals(200, response.code)
        assertEquals(3, server.requestCount)
        response.close()
    }

    @Test
    fun `a POST that creates a task is never replayed`() {
        server.enqueue(MockResponse().setResponseCode(503))

        val request = Request.Builder()
            .url(server.url("/session"))
            .post("{}".toRequestBody())
            .build()
        val response = client.newCall(request).execute()

        assertEquals(503, response.code)
        assertEquals(1, server.requestCount)
        response.close()
    }

    @Test
    fun `a POST may opt in to retries when the caller guarantees idempotency`() {
        server.enqueue(MockResponse().setResponseCode(502))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val request = Request.Builder()
            .url(server.url("/idempotent"))
            .post("{}".toRequestBody())
            .header(RetryInterceptor.IDEMPOTENT_HEADER, "true")
            .build()
        val response = client.newCall(request).execute()

        assertEquals(200, response.code)
        assertEquals(2, server.requestCount)
        response.close()
    }

    @Test
    fun `500 is surfaced immediately instead of being hammered`() {
        server.enqueue(MockResponse().setResponseCode(500))

        val response = client.newCall(Request.Builder().url(server.url("/boom")).build()).execute()

        assertEquals(500, response.code)
        assertEquals(1, server.requestCount)
        response.close()
    }

    @Test
    fun `backoff grows exponentially between attempts`() {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(200))

        client.newCall(Request.Builder().url(server.url("/health")).build()).execute().close()

        assertEquals(listOf(10L, 20L), slept)
    }

    @Test
    fun `Retry-After from a 429 is honoured instead of the default backoff`() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "2"))
        server.enqueue(MockResponse().setResponseCode(200))

        client.newCall(Request.Builder().url(server.url("/limited")).build()).execute().close()

        assertEquals(listOf(2_000L), slept)
    }

    @Test
    fun `a dropped connection on a GET is retried`() {
        // A dedicated client with a longer backoff: on a slow runner the server can need
        // a moment to accept again after DISCONNECT_AT_START.
        val patientClient = OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .callTimeout(10, TimeUnit.SECONDS)
            .addInterceptor(RetryInterceptor(maxAttempts = 4, initialBackoffMillis = 250))
            .build()

        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(200))

        val response = patientClient.newCall(Request.Builder().url(server.url("/flaky")).build()).execute()

        assertEquals(200, response.code)
        response.close()
    }
}
