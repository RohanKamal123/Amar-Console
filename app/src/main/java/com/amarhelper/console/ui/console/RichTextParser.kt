package com.amarhelper.console.ui.console

internal sealed interface RichBlock {
    data class Heading(val level: Int, val text: String) : RichBlock
    data class Paragraph(val text: String) : RichBlock
    data class ListItem(val text: String, val ordered: Boolean, val number: Int?) : RichBlock
    data class Code(val language: String?, val text: String) : RichBlock
    data class Diff(val text: String) : RichBlock
}

internal object RichTextParser {
    private val orderedItem = Regex("""^\s*(\d+)\.\s+(.+)$""")
    private val bulletItem = Regex("""^\s*[-*+]\s+(.+)$""")

    fun parse(source: String): List<RichBlock> {
        if (source.isBlank()) return emptyList()
        val lines = source.replace("\r\n", "\n").lines()
        val blocks = mutableListOf<RichBlock>()
        val paragraph = mutableListOf<String>()
        var index = 0

        fun flushParagraph() {
            if (paragraph.isNotEmpty()) {
                blocks += RichBlock.Paragraph(paragraph.joinToString("\n").trim())
                paragraph.clear()
            }
        }

        while (index < lines.size) {
            val line = lines[index]
            if (line.startsWith("```")) {
                flushParagraph()
                val language = line.removePrefix("```").trim().ifBlank { null }
                val code = mutableListOf<String>()
                index++
                while (index < lines.size && !lines[index].startsWith("```")) code += lines[index++]
                if (index < lines.size) index++
                val content = code.joinToString("\n")
                blocks += if (language == "diff" || looksLikeDiff(content)) {
                    RichBlock.Diff(content)
                } else {
                    RichBlock.Code(language, content)
                }
                continue
            }
            if (looksLikeDiffStart(lines, index)) {
                flushParagraph()
                val diff = mutableListOf<String>()
                while (index < lines.size && lines[index].isNotBlank()) diff += lines[index++]
                blocks += RichBlock.Diff(diff.joinToString("\n"))
                continue
            }
            val headingLevel = line.takeWhile { it == '#' }.length
            when {
                headingLevel in 1..6 && line.getOrNull(headingLevel) == ' ' -> {
                    flushParagraph()
                    blocks += RichBlock.Heading(headingLevel, line.drop(headingLevel + 1))
                }
                orderedItem.matches(line) -> {
                    flushParagraph()
                    val match = orderedItem.matchEntire(line)!!
                    blocks += RichBlock.ListItem(match.groupValues[2], true, match.groupValues[1].toInt())
                }
                bulletItem.matches(line) -> {
                    flushParagraph()
                    blocks += RichBlock.ListItem(bulletItem.matchEntire(line)!!.groupValues[1], false, null)
                }
                line.isBlank() -> flushParagraph()
                else -> paragraph += line
            }
            index++
        }
        flushParagraph()
        return blocks
    }

    fun looksLikeDiff(text: String): Boolean =
        text.lineSequence().any { it.startsWith("@@ ") } &&
            text.lineSequence().any { it.startsWith("--- ") || it.startsWith("+++ ") }

    private fun looksLikeDiffStart(lines: List<String>, index: Int): Boolean =
        lines[index].startsWith("diff --git ") ||
            (lines[index].startsWith("--- ") && lines.getOrNull(index + 1)?.startsWith("+++ ") == true)
}
