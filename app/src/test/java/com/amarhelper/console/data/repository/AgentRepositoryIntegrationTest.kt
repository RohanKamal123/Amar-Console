package com.amarhelper.console.data.repository

import androidx.test.core.app.ApplicationProvider
import com.amarhelper.console.core.result.ApiResult
import com.amarhelper.console.core.result.AppError
import com.amarhelper.console.data.config.ConfigStore
import com.amarhelper.console.data.config.ServiceId
import com.amarhelper.console.data.net.ApiClientFactory
import com.amarhelper.console.data.remote.opencode.OpenCodeEventStream
import com.amarhelper.console.data.security.SecureCredentialStore
import com.amarhelper.console.domain.model.AgentProvider
import com.amarhelper.console.domain.model.TaskState
import com.amarhelper.console.domain.model.TaskSubmission
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Integration coverage for Repository → API client → HTTP, with real configuration
 * storage and a mock server standing in for the backend. This is the layer that would
 * otherwise only be exercised against a live VPS.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentRepositoryIntegrationTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: DefaultAgentRepository
    private lateinit var configStore: ConfigStore

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
        isLenient = true
    }

    @Before
    fun setUp() = runTest {
        server = MockWebServer().apply { start() }
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        configStore = ConfigStore(context)
        configStore.clear()
        val credentials = SecureCredentialStore(context)
        val factory = ApiClientFactory(credentials, json)
        repository = DefaultAgentRepository(configStore, factory, OpenCodeEventStream(credentials, json))
    }

    @After
    fun tearDown() = runTest {
        server.shutdown()
        configStore.clear()
    }

    private suspend fun useOpenCode() {
        configStore.setUrl(ServiceId.OPEN_CODE, server.url("/").toString().trimEnd('/'))
    }

    private suspend fun useOpenHands() {
        configStore.setUrl(ServiceId.OPEN_HANDS, server.url("/").toString().trimEnd('/'))
    }

    @Test
    fun `submitting to OpenCode creates a session then posts the prompt`() = runTest {
        useOpenCode()
        server.enqueue(MockResponse().setBody("""{"id":"ses_1","title":"Auth","time":{"created":1736937000}}"""))
        server.enqueue(MockResponse().setResponseCode(204))

        val result = repository.submitTask(
            TaskSubmission("Build a REST API for user authentication.", AgentProvider.OPEN_CODE),
        )

        assertTrue(result.toString(), result is ApiResult.Success)
        val session = (result as ApiResult.Success).data
        assertEquals("ses_1", session.id)
        assertEquals(TaskState.RUNNING, session.state)
        assertEquals("/session", server.takeRequest().path)
        assertEquals("/session/ses_1/prompt_async", server.takeRequest().path)
    }

    @Test
    fun `a prompt that is rejected after the session is created reports the failure`() = runTest {
        useOpenCode()
        server.enqueue(MockResponse().setBody("""{"id":"ses_1"}"""))
        server.enqueue(MockResponse().setResponseCode(500))

        val result = repository.submitTask(TaskSubmission("Do the thing please", AgentProvider.OPEN_CODE))

        assertTrue((result as ApiResult.Failure).error is AppError.ServerError)
    }

    @Test
    fun `an unconfigured provider is reported rather than attempted`() = runTest {
        val result = repository.submitTask(TaskSubmission("Do the thing please", AgentProvider.OPEN_HANDS))

        assertTrue((result as ApiResult.Failure).error is AppError.NotConfigured)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `sessions from both providers are merged and sorted by recency`() = runTest {
        useOpenCode()
        useOpenHands()
        // OpenHands search, then OpenCode list — order follows the repository's calls.
        server.enqueue(
            MockResponse().setBody(
                """{"items":[{"id":"conv_1","title":"Older","execution_status":"finished",
                   "created_at":"2025-01-15T10:00:00Z","updated_at":"2025-01-15T10:05:00Z"}]}""",
            ),
        )
        server.enqueue(
            MockResponse().setBody("""[{"id":"ses_1","title":"Newer","time":{"created":1900000000,"updated":1900000500}}]"""),
        )

        val sessions = (repository.listSessions() as ApiResult.Success).data

        assertEquals(2, sessions.size)
        assertEquals("Newer", sessions.first().title)
        assertEquals(TaskState.COMPLETED, sessions.last().state)
    }

    @Test
    fun `one dead provider does not hide the other provider's sessions`() = runTest {
        useOpenCode()
        useOpenHands()
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(503)) // OpenHands GET is retried
        server.enqueue(MockResponse().setBody("""[{"id":"ses_1","title":"Alive","time":{"created":1736937000}}]"""))

        val sessions = (repository.listSessions() as ApiResult.Success).data

        assertEquals(1, sessions.size)
        assertEquals("Alive", sessions.first().title)
    }

    @Test
    fun `when every provider fails the error is surfaced`() = runTest {
        useOpenCode()
        server.enqueue(MockResponse().setResponseCode(401))

        val result = repository.listSessions()

        assertTrue((result as ApiResult.Failure).error is AppError.Unauthorized)
    }

    @Test
    fun `OpenCode transcript history maps roles and tool calls`() = runTest {
        useOpenCode()
        server.enqueue(
            MockResponse().setBody(
                """[{"info":{"id":"m1","role":"user","time":{"created":1736937000}},
                     "parts":[{"type":"text","text":"Build it"}]},
                    {"info":{"id":"m2","role":"assistant","time":{"created":1736937100}},
                     "parts":[{"type":"text","text":"On it"},{"type":"tool","tool":"bash"}]}]""",
            ),
        )

        val events = (repository.history(AgentProvider.OPEN_CODE, "ses_1") as ApiResult.Success).data

        assertEquals(3, events.size)
        assertEquals("Build it", events[0].text)
        assertEquals("bash()", events[2].text)
    }

    @Test
    fun `OpenHands transcript replay is reported as unsupported not faked`() = runTest {
        useOpenHands()

        val result = repository.history(AgentProvider.OPEN_HANDS, "conv_1")

        assertTrue((result as ApiResult.Failure).error is AppError.Unsupported)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `cancelling is reported as unsupported on both providers`() = runTest {
        useOpenCode()
        assertTrue((repository.cancel(AgentProvider.OPEN_CODE, "ses_1") as ApiResult.Failure).error is AppError.Unsupported)
        assertTrue((repository.cancel(AgentProvider.OPEN_HANDS, "c") as ApiResult.Failure).error is AppError.Unsupported)
    }

    @Test
    fun `deleting an OpenCode session calls the documented route`() = runTest {
        useOpenCode()
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val result = repository.deleteSession(AgentProvider.OPEN_CODE, "ses_1")

        assertTrue(result is ApiResult.Success)
        val recorded = server.takeRequest()
        assertEquals("DELETE", recorded.method)
        assertEquals("/session/ses_1", recorded.path)
    }

    @Test
    fun `malformed JSON from a compromised or wrong endpoint does not crash the repository`() = runTest {
        useOpenCode()
        server.enqueue(MockResponse().setBody("not json at all"))

        val result = repository.listSessions()

        assertTrue((result as ApiResult.Failure).error is AppError.Malformed)
    }

    @Test
    fun `available providers reflect what the user configured`() = runTest {
        assertEquals(emptyList<AgentProvider>(), repository.availableProviders())
        useOpenCode()
        assertEquals(listOf(AgentProvider.OPEN_CODE), repository.availableProviders())
        useOpenHands()
        assertEquals(listOf(AgentProvider.OPEN_HANDS, AgentProvider.OPEN_CODE), repository.availableProviders())
    }
}
