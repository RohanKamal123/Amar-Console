package com.amarhelper.console.data.net

import com.amarhelper.console.core.result.ApiResult
import com.amarhelper.console.core.result.AppError
import com.amarhelper.console.data.remote.opencode.CreateSessionRequest
import com.amarhelper.console.data.remote.opencode.MessagePartDto
import com.amarhelper.console.data.remote.opencode.OpenCodeApi
import com.amarhelper.console.data.remote.opencode.SendMessageRequest
import com.amarhelper.console.data.remote.openhands.InitSessionRequest
import com.amarhelper.console.data.remote.openhands.OpenHandsApi
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `creating a conversation posts initial_user_msg to the OSS route`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"status":"ok","conversation_id":"fcd7010a","message":null,
                    "conversation_status":"STARTING"}""",
            ),
        )

        val result = safeResponseCall {
            api<OpenHandsApi>().createConversation(
                InitSessionRequest(initialUserMsg = "Build a REST API", repository = "acme/api"),
            )
        }

        val recorded = server.takeRequest()
        assertEquals("/api/conversations", recorded.path)
        assertEquals("POST", recorded.method)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"initial_user_msg\":\"Build a REST API\""))
        assertTrue(body.contains("\"repository\":\"acme/api\""))
        // The Cloud-only wrapper must not reappear.
        assertFalse(body.contains("initial_message"))

        assertEquals("fcd7010a", (result as ApiResult.Success).data.conversationId)
    }

    @Test
    fun `the conversation list parses the OSS result set verbatim`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {"results":[{"conversation_id":"fcd7010a","title":"Auth API",
                  "last_updated_at":"2026-08-15T01:00:00Z","status":"STOPPED",
                  "runtime_status":null,"selected_repository":null,"selected_branch":"master",
                  "git_provider":null,"trigger":"gui","num_connections":0,"url":null,
                  "session_api_key":null,"created_at":"2026-08-14T22:00:00Z","pr_number":[]}],
                 "next_page_id":null}
                """.trimIndent(),
            ),
        )

        val result = safeResponseCall { api<OpenHandsApi>().conversations(limit = 30) }

        assertEquals("/api/conversations?limit=30", server.takeRequest().path)
        val page = (result as ApiResult.Success).data
        val conversation = page.results.single()
        assertEquals("fcd7010a", conversation.conversationId)
        assertEquals("STOPPED", conversation.status)
        assertEquals("master", conversation.selectedBranch)
        assertEquals("gui", conversation.trigger)
        assertEquals(0, conversation.numConnections)
        assertEquals(null, page.nextPageId)
    }

    @Test
    fun `the SPA shell served by the catch-all is reported as malformed`() = runTest {
        // The OSS server answers an unknown path with its frontend and HTTP 200 rather
        // than a 404 — the failure that hid the wrong contract in the first place.
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/html")
                .setBody("<!DOCTYPE html><html><head><title>OpenHands</title></head></html>"),
        )

        val result = safeResponseCall { api<OpenHandsApi>().conversations() }

        assertTrue((result as ApiResult.Failure).error is AppError.Malformed)
    }

    @Test
    fun `options config is JSON and identifies a self-hosted deployment`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"APP_MODE":"oss","GITHUB_CLIENT_ID":"","POSTHOG_CLIENT_KEY":"phc_x",
                    "FEATURE_FLAGS":{"ENABLE_BILLING":false,"HIDE_LLM_SETTINGS":false}}""",
            ),
        )

        val result = safeResponseCall { api<OpenHandsApi>().optionsConfig() }

        assertEquals("/api/options/config", server.takeRequest().path)
        assertEquals("oss", (result as ApiResult.Success).data.appMode)
    }

    @Test
    fun `stopping and deleting a conversation use the OSS routes`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        safeResponseCall { api<OpenHandsApi>().stopConversation("fcd7010a") }
        safeResponseCall { api<OpenHandsApi>().deleteConversation("fcd7010a") }

        val stop = server.takeRequest()
        assertEquals("POST", stop.method)
        assertEquals("/api/conversations/fcd7010a/stop", stop.path)
        val delete = server.takeRequest()
        assertEquals("DELETE", delete.method)
        assertEquals("/api/conversations/fcd7010a", delete.path)
    }

    @Test
    fun `the event transcript is requested within the server's limit cap`() = runTest {
        server.enqueue(MockResponse().setBody("""{"events":[],"has_more":false}"""))

        safeResponseCall { api<OpenHandsApi>().events("fcd7010a") }

        val path = server.takeRequest().path.orEmpty()
        assertTrue(path.startsWith("/api/conversations/fcd7010a/events"))
        // The server rejects limit > 100 with a 400, so the client must never exceed it.
        val limit = Regex("limit=(\\d+)").find(path)!!.groupValues[1].toInt()
        assertTrue("limit was $limit", limit in 1..100)
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

        val result = safeResponseCall { api<OpenHandsApi>().conversations() }

        assertTrue((result as ApiResult.Failure).error is AppError.Malformed)
    }
}
