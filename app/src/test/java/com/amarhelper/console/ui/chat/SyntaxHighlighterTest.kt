package com.amarhelper.console.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntaxHighlighterTest {

    private fun typesOf(line: String, language: String?) =
        SyntaxHighlighter.tokenize(line, language).filter { it.text.isNotBlank() }

    @Test
    fun `tokenizing never loses or reorders characters`() {
        val line = """val greeting = "hello ${'$'}name" // a comment"""
        val roundTrip = SyntaxHighlighter.tokenize(line, "kotlin").joinToString("") { it.text }
        assertEquals(line, roundTrip)
    }

    @Test
    fun `keywords are recognised per language`() {
        assertTrue(typesOf("val x = 1", "kotlin").any { it.text == "val" && it.type == Token.Type.Keyword })
        assertTrue(typesOf("def x():", "python").any { it.text == "def" && it.type == Token.Type.Keyword })
        assertTrue(typesOf("const x = 1", "javascript").any { it.text == "const" && it.type == Token.Type.Keyword })
    }

    @Test
    fun `a keyword from another language is not highlighted`() {
        assertTrue(typesOf("def x = 1", "kotlin").none { it.type == Token.Type.Keyword })
    }

    @Test
    fun `strings and numbers are separated`() {
        val tokens = typesOf("""x = "text" + 42""", "python")
        assertTrue(tokens.any { it.type == Token.Type.StringLiteral && it.text.contains("text") })
        assertTrue(tokens.any { it.type == Token.Type.Number && it.text == "42" })
    }

    @Test
    fun `comment syntax follows the language`() {
        assertTrue(typesOf("# a note", "python").any { it.type == Token.Type.Comment })
        assertTrue(typesOf("// a note", "kotlin").any { it.type == Token.Type.Comment })
    }

    @Test
    fun `a hash inside a string is not treated as a comment`() {
        val tokens = SyntaxHighlighter.tokenize("""cmd = "grep # pattern"""", "python")
        assertTrue(tokens.none { it.type == Token.Type.Comment })
    }

    @Test
    fun `an escaped quote does not end the string early`() {
        val tokens = SyntaxHighlighter.tokenize("""x = "a \" b" """, "kotlin")
        val literal = tokens.single { it.type == Token.Type.StringLiteral }
        assertTrue(literal.text.contains("\\\""))
    }

    @Test
    fun `an unknown language still highlights strings and numbers`() {
        val tokens = typesOf("""set foo = "bar" 7""", "brainfuck")
        assertTrue(tokens.any { it.type == Token.Type.StringLiteral })
        assertTrue(tokens.any { it.type == Token.Type.Number })
    }

    @Test
    fun `an empty line produces a single empty token`() {
        assertEquals(1, SyntaxHighlighter.tokenize("", "kotlin").size)
    }
}
