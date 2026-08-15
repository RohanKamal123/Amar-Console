package com.amarhelper.console.data.remote

import androidx.test.core.app.ApplicationProvider
import com.amarhelper.console.data.remote.opencode.OpenCodeEventStream
import com.amarhelper.console.data.security.SecureCredentialStore
import com.amarhelper.console.domain.model.ConsoleEvent
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Parsing of OpenCode's server-sent event frames, over a real socket. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenCodeEventStreamTest {

    private lateinit var server: MockWebServer
    private lateinit var stream: OpenCodeEventStream

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        stream = OpenCodeEventStream(SecureCredentialStore(context), Json { ignoreUnknownKeys = true })
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun sse(vararg frames: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(frames.joinToString("") { "data: $it\n\n" })

    private fun url() = server.url("/").toString().trimEnd('/')

    @Test
    fun `text and tool parts become console lines`() = runTest {
        server.enqueue(
            sse(
                """{"type":"server.connected","properties":{}}""",
                """{"type":"message.part.updated","properties":{"sessionID":"ses_1","part":{"id":"p1","type":"text","text":"Working on it"}}}""",
                """{"type":"message.part.updated","properties":{"sessionID":"ses_1","part":{"id":"p2","type":"tool","tool":"bash"}}}""",
            ),
        )

        val events = stream.events(url(), "ses_1").take(2).toList()

        assertEquals(ConsoleEvent.Kind.AGENT, events[0].kind)
        assertEquals("Working on it", events[0].text)
        assertEquals(ConsoleEvent.Kind.TOOL, events[1].kind)
        assertEquals("bash()", events[1].text)
    }

    @Test
    fun `events for other sessions are ignored`() = runTest {
        server.enqueue(
            sse(
                """{"type":"message.part.updated","properties":{"sessionID":"other","part":{"id":"p0","type":"text","text":"not mine"}}}""",
                """{"type":"message.part.updated","properties":{"sessionID":"ses_1","part":{"id":"p1","type":"text","text":"mine"}}}""",
            ),
        )

        val events = stream.events(url(), "ses_1").take(1).toList()

        assertEquals("mine", events.single().text)
    }

    @Test
    fun `an unparseable frame is dropped instead of killing the stream`() = runTest {
        server.enqueue(
            sse(
                "{not json",
                """{"type":"message.part.updated","properties":{"sessionID":"ses_1","part":{"id":"p1","type":"text","text":"survived"}}}""",
            ),
        )

        val events = stream.events(url(), "ses_1").take(1).toList()

        assertEquals("survived", events.single().text)
    }

    @Test
    fun `error frames are surfaced without echoing the raw payload`() = runTest {
        server.enqueue(
            sse(
                """{"type":"session.error","properties":{"sessionID":"ses_1","error":{"token":"secret-value"}}}""",
                """{"type":"session.idle","properties":{"sessionID":"ses_1"}}""",
            ),
        )

        val events = stream.events(url(), "ses_1").take(2).toList()

        assertEquals(ConsoleEvent.Kind.ERROR, events[0].kind)
        assertFalse(events[0].text.contains("secret-value"))
        assertEquals(ConsoleEvent.Kind.SYSTEM, events[1].kind)
    }

    @Test
    fun `a rejected stream fails the flow so the UI can offer reconnect`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val error = runCatching { stream.events(url(), "ses_1").take(1).toList() }.exceptionOrNull()

        assertTrue(error is java.io.IOException)
    }
}
