package com.amarhelper.console.data.net

import com.amarhelper.console.core.result.ApiResult
import com.amarhelper.console.core.result.AppError
import com.amarhelper.console.data.remote.opencode.CreateSessionRequest
import com.amarhelper.console.data.remote.opencode.MessagePartDto
import com.amarhelper.console.data.remote.opencode.OpenCodeApi
import com.amarhelper.console.data.remote.opencode.SendMessageRequest
import com.amarhelper.console.data.remote.openhands.CreateConversationRequest
import com.amarhelper.console.data.remote.openhands.InitialMessage
import com.amarhelper.console.data.remote.openhands.MessageContent
import com.amarhelper.console.data.remote.openhands.OpenHandsApi
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit

/**
 * Verifies the app speaks the documented wire protocol: request paths, request bodies
 * and response parsing, checked against fixtures shaped like the published examples in
 * the OpenHands and OpenCode API references.
 */
class ApiContractTest {

    private lateinit var server: MockWebServer

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
        isLenient = true
    }

    private inline fun <reified T> api(): T = Retrofit.Builder()
        .baseUrl(server.url("/"))
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(T::class.java)

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `creating an OpenHands conversation posts the documented body to the documented path`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {"id":"task-1","status":"WORKING","app_conversation_id":"conv-9",
                 "sandbox_id":"sbx-2","created_at":"2025-01-15T10:30:00Z"}
                """.trimIndent(),
            ),
        )

        val result = safeResponseCall {
            api<OpenHandsApi>().createConversation(
                CreateConversationRequest(
                    initialMessage = InitialMessage(listOf(MessageContent(text = "Build a REST API"))),
                    selectedRepository = "acme/api",
                ),
            )
        }

        val recorded = server.takeRequest()
        assertEquals("/api/v1/app-conversations", recorded.path)
        assertEquals("POST", recorded.method)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"initial_message\""))
        assertTrue(body.contains("\"selected_repository\":\"acme/api\""))
        assertTrue(body.contains("Build a REST API"))

        val success = result as ApiResult.Success
        assertEquals("conv-9", success.data.appConversationId)
        assertEquals("sbx-2", success.data.sandboxId)
    }

    @Test
    fun `conversation search parses the paged response`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {"items":[{"id":"conv-9","title":"Auth API","sandbox_status":"RUNNING",
                 "execution_status":"running","selected_repository":"acme/api",
                 "created_at":"2025-01-15T10:30:00Z"}],"next_page_id":"p2"}
                """.trimIndent(),
            ),
        )

        val result = safeResponseCall { api<OpenHandsApi>().searchConversations(limit = 20) }

        assertEquals("/api/v1/app-conversations/search?limit=20", server.takeRequest().path)
        val page = (result as ApiResult.Success).data
        assertEquals(1, page.items.size)
        assertEquals("Auth API", page.items.first().title)
        assertEquals("running", page.items.first().executionStatus)
        assertEquals("p2", page.nextPageId)
    }

    @Test
    fun `unknown fields in a response do not break parsing`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"items":[{"id":"c1","title":"x","brand_new_field":{"nested":true}}]}""",
            ),
        )

        val result = safeResponseCall { api<OpenHandsApi>().searchConversations() }

        assertTrue(result is ApiResult.Success)
        assertEquals("c1", (result as ApiResult.Success).data.items.first().id)
    }

    @Test
    fun `an HTML error page is reported as malformed rather than crashing`() = runTest {
        server.enqueue(MockResponse().setBody("<html><body>502 Bad Gateway</body></html>"))

        val result = safeResponseCall { api<OpenHandsApi>().searchConversations() }

        assertTrue((result as ApiResult.Failure).error is AppError.Malformed)
    }

    @Test
    fun `401 from OpenHands becomes an unauthorized error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"invalid key"}"""))

        val result = safeResponseCall { api<OpenHandsApi>().searchConversations() }

        assertTrue((result as ApiResult.Failure).error is AppError.Unauthorized)
    }

    @Test
    fun `OpenCode health reports version`() = runTest {
        server.enqueue(MockResponse().setBody("""{"healthy":true,"version":"0.4.11"}"""))

        val result = safeResponseCall { api<OpenCodeApi>().health() }

        assertEquals("/global/health", server.takeRequest().path)
        assertEquals("0.4.11", (result as ApiResult.Success).data.version)
    }

    @Test
    fun `creating an OpenCode session and posting a prompt hits the documented routes`() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"ses_1","title":"Auth","time":{"created":1736937000}}"""))
        server.enqueue(MockResponse().setResponseCode(204))

        val created = safeResponseCall { api<OpenCodeApi>().createSession(CreateSessionRequest(title = "Auth")) }
        val sessionId = (created as ApiResult.Success).data.id
        safeResponseCall {
            api<OpenCodeApi>().sendPromptAsync(
                sessionId,
                SendMessageRequest(parts = listOf(MessagePartDto(text = "Build a REST API"))),
            )
        }

        assertEquals("/session", server.takeRequest().path)
        val prompt = server.takeRequest()
        assertEquals("/session/ses_1/prompt_async", prompt.path)
        assertTrue(prompt.body.readUtf8().contains("Build a REST API"))
    }

    @Test
    fun `OpenCode message envelopes parse into parts`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                [{"info":{"id":"m1","role":"user","time":{"created":1736937000}},
                  "parts":[{"type":"text","text":"Build a REST API"}]},
                 {"info":{"id":"m2","role":"assistant","time":{"created":1736937100}},
                  "parts":[{"type":"text","text":"Working on it"},{"type":"tool","tool":"bash"}]}]
                """.trimIndent(),
            ),
        )

        val result = safeResponseCall { api<OpenCodeApi>().messages("ses_1") }

        val messages = (result as ApiResult.Success).data
        assertEquals(2, messages.size)
        assertEquals("user", messages[0].info?.role)
        assertEquals(2, messages[1].parts.size)
        assertEquals("bash", messages[1].parts[1].tool)
    }

    @Test
    fun `an empty body on a 200 is reported rather than silently succeeding`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        val result = safeResponseCall { api<OpenHandsApi>().searchConversations() }

        assertTrue((result as ApiResult.Failure).error is AppError.Malformed)
    }
}
