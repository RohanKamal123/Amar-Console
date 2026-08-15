package com.amarhelper.console.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {

    @Test
    fun `headings carry their level`() {
        val blocks = MarkdownParser.parse("# One\n## Two\n### Three")
        assertEquals(listOf(1, 2, 3), blocks.map { (it as ChatBlock.Heading).level })
    }

    @Test
    fun `a fenced block keeps its language and every line`() {
        val blocks = MarkdownParser.parse(
            """
            Here you go:

            ```kotlin
            fun main() {
                println("hi")
            }
            ```
            """.trimIndent(),
        )
        val code = blocks.filterIsInstance<ChatBlock.Code>().single()
        assertEquals("kotlin", code.language)
        assertEquals(3, code.lines.size)
        assertTrue(code.raw.contains("println"))
    }

    @Test
    fun `an unterminated fence still renders as code rather than swallowing the output`() {
        val code = MarkdownParser.parse("```\nnpm install\n").filterIsInstance<ChatBlock.Code>().single()
        assertEquals(listOf("npm install"), code.lines.map { line -> line.joinToString("") { it.text } })
    }

    @Test
    fun `bullet and numbered lists are distinguished`() {
        val blocks = MarkdownParser.parse("- one\n- two\n\n1. first\n2. second")
        assertEquals(2, (blocks[0] as ChatBlock.BulletList).items.size)
        assertEquals(2, (blocks[1] as ChatBlock.NumberedList).items.size)
    }

    @Test
    fun `nested list items record their depth`() {
        val list = MarkdownParser.parse("- top\n  - nested").first() as ChatBlock.BulletList
        assertEquals(0, list.items[0].depth)
        assertEquals(1, list.items[1].depth)
    }

    @Test
    fun `inline emphasis and code are separate spans`() {
        val spans = MarkdownParser.parseInline("plain **bold** and `code` and *italic*")
        assertTrue(spans.any { it.bold && it.text == "bold" })
        assertTrue(spans.any { it.code && it.text == "code" })
        assertTrue(spans.any { it.italic && it.text == "italic" })
    }

    @Test
    fun `markers inside a code span stay literal`() {
        // An agent quoting a glob or a regex must not have it eaten as emphasis.
        val spans = MarkdownParser.parseInline("run `ls **/*.kt` now")
        assertEquals("ls **/*.kt", spans.single { it.code }.text)
        assertTrue(spans.none { it.bold })
    }

    @Test
    fun `an unmatched marker is left as text instead of dropping the rest of the line`() {
        val spans = MarkdownParser.parseInline("2 * 3 = 6")
        assertEquals("2 * 3 = 6", spans.joinToString("") { it.text })
    }

    @Test
    fun `links keep both label and target`() {
        val span = MarkdownParser.parseInline("see [the docs](https://example.com/x)").single { it.link != null }
        assertEquals("the docs", span.text)
        assertEquals("https://example.com/x", span.link)
    }

    @Test
    fun `a diff fence becomes a diff block, not code`() {
        val blocks = MarkdownParser.parse(
            """
            ```diff
            --- a/app.py
            +++ b/app.py
            @@ -1,3 +1,4 @@
            -old
            +new
            ```
            """.trimIndent(),
        )
        assertTrue(blocks.single() is ChatBlock.Diff)
    }

    @Test
    fun `an unfenced unified diff is still recognised`() {
        val blocks = MarkdownParser.parse(
            "--- a/README.md\n+++ b/README.md\n@@ -1 +1 @@\n-a\n+b",
        )
        assertTrue(blocks.single() is ChatBlock.Diff)
    }

    @Test
    fun `block quotes nest their content`() {
        val quote = MarkdownParser.parse("> quoted **text**").single() as ChatBlock.Quote
        assertTrue(quote.blocks.single() is ChatBlock.Paragraph)
    }

    @Test
    fun `horizontal rules are recognised without eating list markers`() {
        assertTrue(MarkdownParser.parse("---").single() is ChatBlock.Divider)
        assertTrue(MarkdownParser.parse("- item").single() is ChatBlock.BulletList)
    }

    @Test
    fun `plain text survives untouched`() {
        val paragraph = MarkdownParser.parse("just a sentence").single() as ChatBlock.Paragraph
        assertEquals("just a sentence", paragraph.spans.joinToString("") { it.text })
    }

    @Test
    fun `empty input produces no blocks rather than a blank bubble`() {
        assertTrue(MarkdownParser.parse("").isEmpty())
        assertTrue(MarkdownParser.parse("\n\n").isEmpty())
    }
}
