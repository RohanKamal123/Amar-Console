package com.amarhelper.console.ui.console

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RichTextParserTest {
    @Test
    fun `parses headings lists paragraphs and fenced code`() {
        val blocks = RichTextParser.parse(
            """
            # Result

            **Done** successfully.

            - first
            2. second

            ```kotlin
            val answer = 42
            ```
            """.trimIndent(),
        )

        assertEquals(RichBlock.Heading(1, "Result"), blocks[0])
        assertEquals(RichBlock.Paragraph("**Done** successfully."), blocks[1])
        assertEquals(RichBlock.ListItem("first", false, null), blocks[2])
        assertEquals(RichBlock.ListItem("second", true, 2), blocks[3])
        assertEquals(RichBlock.Code("kotlin", "val answer = 42"), blocks[4])
    }

    @Test
    fun `renders fenced and bare unified diffs as diff blocks`() {
        val fenced = RichTextParser.parse("```diff\n--- a/file\n+++ b/file\n@@ -1 +1 @@\n-old\n+new\n```")
        val bare = RichTextParser.parse("--- a/file\n+++ b/file\n@@ -1 +1 @@\n-old\n+new")

        assertTrue(fenced.single() is RichBlock.Diff)
        assertTrue(bare.single() is RichBlock.Diff)
    }

    @Test
    fun `unterminated fence remains a code block`() {
        assertEquals(
            RichBlock.Code("sh", "echo hello"),
            RichTextParser.parse("```sh\necho hello").single(),
        )
    }
}
