package com.amarhelper.console.ui.workspace

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkspaceStyleInjectorTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `the script embeds the stylesheet and leaves no placeholder behind`() {
        val script = WorkspaceStyleInjector.script(context)

        assertNotNull(script)
        assertFalse(script!!.contains("__CLAUDE_CSS__"))
        assertTrue(script.contains("claude-workspace-style"))
        assertTrue(script.contains("data-claude-style"))
    }

    @Test
    fun `the stylesheet targets the selectors the deployed frontend actually emits`() {
        val script = WorkspaceStyleInjector.script(context)!!

        // Taken from the OpenHands frontend source at 0.62.0. If an upgrade renames
        // these, this test is the first place it should show up.
        listOf(
            "user-message",
            "agent-message",
            "chat-input",
            "interactive-chat-box",
            "submit-button",
            "root-layout",
        ).forEach { testId ->
            assertTrue("missing selector for $testId", script.contains(testId))
        }
    }

    @Test
    fun `a quote or backslash in the stylesheet cannot break out of the literal`() {
        val quoted = WorkspaceStyleInjector.quoteForJs("""a" \ b""")

        assertTrue(quoted.startsWith("\""))
        assertTrue(quoted.endsWith("\""))
        assertTrue(quoted.contains("\\\""))
        assertTrue(quoted.contains("\\\\"))
    }

    @Test
    fun `newlines and line separators are escaped rather than ending the statement`() {
        // U+2028 and U+2029 terminate a JavaScript line even inside a string literal,
        // so they have to be escaped as surely as a newline does.
        val quoted = WorkspaceStyleInjector.quoteForJs("a\nb\r\u2028c\u2029d")

        assertFalse(quoted.contains('\n'))
        assertFalse(quoted.contains('\r'))
        assertFalse(quoted.contains('\u2028'))
        assertFalse(quoted.contains('\u2029'))
        assertTrue(quoted.contains("\\u2028"))
        assertTrue(quoted.contains("\\u2029"))
    }

    @Test
    fun `a closing script tag in the stylesheet is neutralised`() {
        val quoted = WorkspaceStyleInjector.quoteForJs("</script><script>alert(1)</script>")

        assertFalse(quoted.contains("</script>"))
        assertTrue(quoted.contains("\\u003C"))
    }

    @Test
    fun `the stylesheet never overrides structure on the page's layout containers`() {
        // The page hangs its height off a percentage chain: root-layout's child is
        // h-[calc(100%-50px)] and app-route is h-full. An earlier version of this
        // stylesheet forced `display: block` on root-layout, which collapsed that chain
        // and rendered the whole workspace blank. Colour is safe here; structure is not.
        val css = context.assets.open("claude_workspace.css").bufferedReader().use { it.readText() }
        val structural = listOf("display", "height", "position", "flex", "overflow")

        listOf("root-layout", "app-route").forEach { container ->
            val ruleStart = css.indexOf(container)
            if (ruleStart < 0) return@forEach
            val blockStart = css.indexOf('{', ruleStart)
            val blockEnd = css.indexOf('}', blockStart)
            val declarations = css.substring(blockStart, blockEnd)

            structural.forEach { property ->
                assertFalse(
                    "$container must not set $property — it breaks the page's height chain",
                    declarations.contains("$property:"),
                )
            }
        }
    }

    @Test
    fun `the script is cached rather than re-read on every navigation`() {
        val first = WorkspaceStyleInjector.script(context)
        val second = WorkspaceStyleInjector.script(context)

        assertTrue(first === second)
    }
}
