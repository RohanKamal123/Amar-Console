package com.amarhelper.console.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffParserTest {

    private val sample = """
        diff --git a/app.py b/app.py
        --- a/app.py
        +++ b/app.py
        @@ -1,4 +1,5 @@
         import os
        -old_line()
        +new_line()
        +extra_line()
    """.trimIndent()

    @Test
    fun `each line is classified`() {
        val lines = DiffParser.parse(sample)
        assertEquals(DiffLine.Kind.FileHeader, lines[0].kind)
        assertEquals(DiffLine.Kind.FileHeader, lines[1].kind)
        assertEquals(DiffLine.Kind.HunkHeader, lines[3].kind)
        assertEquals(DiffLine.Kind.Context, lines[4].kind)
        assertEquals(DiffLine.Kind.Removed, lines[5].kind)
        assertEquals(DiffLine.Kind.Added, lines[6].kind)
    }

    @Test
    fun `the marker is stripped so the text aligns`() {
        val added = DiffParser.parse(sample).first { it.kind == DiffLine.Kind.Added }
        assertEquals("new_line()", added.text)
    }

    @Test
    fun `added and removed counts drive the summary`() {
        val (added, removed) = DiffParser.summarize(DiffParser.parse(sample))
        assertEquals(2, added)
        assertEquals(1, removed)
    }

    @Test
    fun `prose with a leading dash is not mistaken for a diff`() {
        assertFalse(DiffParser.looksLikeDiff("- first point\n- second point"))
        assertFalse(DiffParser.looksLikeDiff("A sentence about a-b testing."))
    }

    @Test
    fun `a hunk header alone is enough to recognise a diff`() {
        assertTrue(DiffParser.looksLikeDiff("@@ -1,2 +1,3 @@\n context\n+added"))
    }

    @Test
    fun `file headers alone are enough`() {
        assertTrue(DiffParser.looksLikeDiff("--- a/x\n+++ b/x\n context"))
    }
}
