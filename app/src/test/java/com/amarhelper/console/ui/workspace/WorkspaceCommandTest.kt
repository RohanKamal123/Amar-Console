package com.amarhelper.console.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceCommandTest {

    @Test
    fun `a command parses with its argument`() {
        val parsed = WorkspaceCommand.parse("/new fix the failing auth test")!!

        assertEquals(WorkspaceCommand.NEW, parsed.command)
        assertEquals("fix the failing auth test", parsed.argument)
    }

    @Test
    fun `a command without an argument reports none rather than an empty string`() {
        assertNull(WorkspaceCommand.parse("/stop")!!.argument)
        assertNull(WorkspaceCommand.parse("/stop   ")!!.argument)
    }

    @Test
    fun `ordinary text is not a command`() {
        // The bar must not swallow a message meant for the page's own input box.
        assertNull(WorkspaceCommand.parse("stop the agent"))
        assertNull(WorkspaceCommand.parse("what is /stop"))
        assertNull(WorkspaceCommand.parse(""))
    }

    @Test
    fun `an unknown slash word is not silently treated as a command`() {
        assertNull(WorkspaceCommand.parse("/deploy production"))
    }

    @Test
    fun `commands are matched case-insensitively`() {
        assertEquals(WorkspaceCommand.STOP, WorkspaceCommand.parse("/STOP")!!.command)
    }

    @Test
    fun `the palette narrows as the token is typed`() {
        assertEquals(WorkspaceCommand.entries.size, WorkspaceCommand.matching("/").size)
        assertEquals(listOf(WorkspaceCommand.SESSIONS, WorkspaceCommand.SETTINGS), WorkspaceCommand.matching("/se"))
        assertEquals(listOf(WorkspaceCommand.STOP), WorkspaceCommand.matching("/sto"))
        assertTrue(WorkspaceCommand.matching("hello").isEmpty())
    }

    @Test
    fun `the conversation id is read from the frontend's route`() {
        // frontend/src/routes.ts at 0.62.0: route("conversations/:conversationId", ...)
        assertEquals(
            "fcd7010a",
            WorkspaceUrl.conversationId("http://100.87.52.65:3000/conversations/fcd7010a"),
        )
        assertEquals(
            "fcd7010a",
            WorkspaceUrl.conversationId("http://host.ts.net:3000/conversations/fcd7010a?tab=browser"),
        )
    }

    @Test
    fun `pages that are not a conversation report no id`() {
        assertNull(WorkspaceUrl.conversationId("http://100.87.52.65:3000/"))
        assertNull(WorkspaceUrl.conversationId("http://100.87.52.65:3000/settings"))
        assertNull(WorkspaceUrl.conversationId("http://100.87.52.65:3000/conversations/"))
        assertNull(WorkspaceUrl.conversationId(null))
    }

    @Test
    fun `built urls do not double their slashes`() {
        assertEquals(
            "http://host:3000/conversations/abc",
            WorkspaceUrl.conversationUrl("http://host:3000/", "abc"),
        )
        assertEquals("http://host:3000/settings", WorkspaceUrl.settingsUrl("http://host:3000/"))
        assertEquals("http://host:3000", WorkspaceUrl.sessionsUrl("http://host:3000/"))
    }

    @Test
    fun `clearing the cache is reachable without a conversation`() {
        val parsed = WorkspaceCommand.parse("/clearcache")!!

        assertEquals(WorkspaceCommand.CLEARCACHE, parsed.command)
        assertTrue(!parsed.command.needsConversation)
        assertEquals(
            listOf(WorkspaceCommand.CHROME, WorkspaceCommand.CLEARCACHE),
            WorkspaceCommand.matching("/c"),
        )
    }

    @Test
    fun `the control that injects nothing is its own command`() {
        // Turning off Claude styling only stops the stylesheet — the error listener still
        // runs — so it never was a test of "no app code in the page".
        assertEquals(WorkspaceCommand.PLAIN, WorkspaceCommand.parse("/plain")!!.command)
        assertTrue(!WorkspaceCommand.PLAIN.needsConversation)
    }

    @Test
    fun `only commands needing a conversation are marked as such`() {
        assertTrue(WorkspaceCommand.STOP.needsConversation)
        assertTrue(!WorkspaceCommand.NEW.needsConversation)
        assertTrue(!WorkspaceCommand.SESSIONS.needsConversation)
    }
}
