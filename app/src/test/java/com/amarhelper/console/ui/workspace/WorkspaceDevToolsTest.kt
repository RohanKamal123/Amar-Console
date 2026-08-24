package com.amarhelper.console.ui.workspace

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkspaceDevToolsTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `the sentinel url is served from assets as javascript`() {
        val response = WorkspaceDevTools.interceptOrNull(context, WorkspaceDevTools.ERUDA_URL)

        assertNotNull(response)
        assertEquals("application/javascript", response!!.mimeType)
        assertEquals("utf-8", response.encoding)
        assertNotNull(response.data)
    }

    @Test
    fun `the bundled tool is the real thing and not a stub`() {
        val bytes = context.assets.open("eruda.js").use { it.readBytes() }

        // Guards against the asset being replaced by a placeholder or truncated: a
        // debugging tool that silently does nothing is worse than none.
        assertTrue("unexpectedly small: ${bytes.size}", bytes.size > 100_000)
        assertTrue(String(bytes.copyOfRange(0, 120)).contains("eruda"))
    }

    @Test
    fun `every other request is left alone`() {
        // The interceptor sits in front of every page request; anything but the sentinel
        // must fall through untouched.
        assertNull(WorkspaceDevTools.interceptOrNull(context, "http://100.87.52.65:3000/"))
        assertNull(WorkspaceDevTools.interceptOrNull(context, "http://host/assets/root-abc.js"))
        assertNull(WorkspaceDevTools.interceptOrNull(context, null))
    }

    @Test
    fun `the loader reports rather than failing silently`() {
        val script = WorkspaceDevTools.LOADER_SCRIPT

        assertTrue(script.contains("onerror"))
        assertTrue(script.contains("already running"))
        assertTrue(script.contains("loading"))
        assertTrue(script.contains(WorkspaceDevTools.ERUDA_URL))
    }
}
