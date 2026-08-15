package com.amarhelper.console.ui.chat

/**
 * A small, dependency-free markdown parser covering what agents actually emit:
 * headings, fenced code, bullet and numbered lists, block quotes, horizontal rules, and
 * inline bold/italic/code/links.
 *
 * It is deliberately not a full CommonMark implementation — agent output is generated
 * markdown, not hand-written prose with edge cases — and being hand-rolled keeps a
 * parser dependency out of an app whose whole point is talking to a backend. Anything it
 * does not recognise degrades to a plain paragraph rather than being dropped.
 */
object MarkdownParser {

    private val fenceRegex = Regex("""^\s*```\s*([A-Za-z0-9+#_-]*)\s*$""")
    private val headingRegex = Regex("""^(#{1,6})\s+(.*)$""")
    private val bulletRegex = Regex("""^(\s*)[-*+]\s+(.*)$""")
    private val numberedRegex = Regex("""^(\s*)\d+[.)]\s+(.*)$""")
    private val quoteRegex = Regex("""^\s*>\s?(.*)$""")
    // A backreference cannot appear inside a character class, so the three rule
    // characters are spelled out instead.
    private val ruleRegex = Regex("""^\s*(?:-{3,}|\*{3,}|_{3,})\s*$""")

    fun parse(markdown: String): List<ChatBlock> {
        val lines = markdown.replace("\r\n", "\n").split('\n')
        val blocks = mutableListOf<ChatBlock>()
        var index = 0

        while (index < lines.size) {
            val line = lines[index]

            when {
                line.isBlank() -> index++

                fenceRegex.matches(line) -> {
                    val language = fenceRegex.find(line)!!.groupValues[1].takeIf { it.isNotBlank() }
                    val body = mutableListOf<String>()
                    index++
                    while (index < lines.size && !fenceRegex.matches(lines[index])) {
                        body += lines[index]
                        index++
                    }
                    index++ // closing fence, if present
                    blocks += codeOrDiff(language, body)
                }

                ruleRegex.matches(line) -> {
                    blocks += ChatBlock.Divider
                    index++
                }

                headingRegex.matches(line) -> {
                    val match = headingRegex.find(line)!!
                    blocks += ChatBlock.Heading(
                        level = match.groupValues[1].length,
                        spans = parseInline(match.groupValues[2]),
                    )
                    index++
                }

                quoteRegex.matches(line) -> {
                    val body = mutableListOf<String>()
                    while (index < lines.size && quoteRegex.matches(lines[index])) {
                        body += quoteRegex.find(lines[index])!!.groupValues[1]
                        index++
                    }
                    blocks += ChatBlock.Quote(parse(body.joinToString("\n")))
                }

                bulletRegex.matches(line) -> {
                    val items = mutableListOf<ListItem>()
                    while (index < lines.size && bulletRegex.matches(lines[index])) {
                        val match = bulletRegex.find(lines[index])!!
                        items += ListItem(
                            spans = parseInline(match.groupValues[2]),
                            depth = match.groupValues[1].length / 2,
                        )
                        index++
                    }
                    blocks += ChatBlock.BulletList(items)
                }

                numberedRegex.matches(line) -> {
                    val items = mutableListOf<ListItem>()
                    while (index < lines.size && numberedRegex.matches(lines[index])) {
                        val match = numberedRegex.find(lines[index])!!
                        items += ListItem(
                            spans = parseInline(match.groupValues[2]),
                            depth = match.groupValues[1].length / 2,
                        )
                        index++
                    }
                    blocks += ChatBlock.NumberedList(items)
                }

                else -> {
                    val body = mutableListOf<String>()
                    while (index < lines.size && lines[index].isNotBlank() &&
                        !fenceRegex.matches(lines[index]) &&
                        !headingRegex.matches(lines[index]) &&
                        !bulletRegex.matches(lines[index]) &&
                        !numberedRegex.matches(lines[index]) &&
                        !quoteRegex.matches(lines[index]) &&
                        !ruleRegex.matches(lines[index])
                    ) {
                        body += lines[index]
                        index++
                    }
                    // A bare unified diff, pasted without a fence, is still a diff.
                    val text = body.joinToString("\n")
                    blocks += if (DiffParser.looksLikeDiff(text)) {
                        ChatBlock.Diff(DiffParser.parse(text))
                    } else {
                        ChatBlock.Paragraph(parseInline(text))
                    }
                }
            }
        }
        return blocks
    }

    private fun codeOrDiff(language: String?, rawBody: List<String>): ChatBlock {
        // Trailing blank lines render as phantom empty rows in a code block — most often
        // from an unterminated fence, where the rest of the message became the body.
        val body = rawBody.dropLastWhile { it.isBlank() }
        val text = body.joinToString("\n")
        val isDiff = language?.lowercase() in setOf("diff", "patch") || DiffParser.looksLikeDiff(text)
        return if (isDiff) {
            ChatBlock.Diff(DiffParser.parse(text))
        } else {
            ChatBlock.Code(
                language = language,
                lines = body.map { SyntaxHighlighter.tokenize(it, language) },
                raw = text,
            )
        }
    }

    /**
     * Inline formatting. Code spans are resolved first so `**` inside backticks stays
     * literal, which matters when an agent quotes shell or regex.
     */
    fun parseInline(text: String): List<Span> {
        if (text.isEmpty()) return listOf(Span(""))
        val spans = mutableListOf<Span>()
        var index = 0
        val buffer = StringBuilder()

        fun flush() {
            if (buffer.isNotEmpty()) {
                spans += Span(buffer.toString())
                buffer.clear()
            }
        }

        while (index < text.length) {
            val rest = text.substring(index)
            when {
                rest.startsWith("`") -> {
                    val end = rest.indexOf('`', startIndex = 1)
                    if (end > 0) {
                        flush()
                        spans += Span(rest.substring(1, end), code = true)
                        index += end + 1
                    } else {
                        buffer.append(text[index]); index++
                    }
                }

                rest.startsWith("**") -> {
                    val end = rest.indexOf("**", startIndex = 2)
                    if (end > 0) {
                        flush()
                        spans += Span(rest.substring(2, end), bold = true)
                        index += end + 2
                    } else {
                        buffer.append(text[index]); index++
                    }
                }

                rest.startsWith("*") || rest.startsWith("_") -> {
                    val marker = rest[0]
                    val end = rest.indexOf(marker, startIndex = 1)
                    if (end > 0) {
                        flush()
                        spans += Span(rest.substring(1, end), italic = true)
                        index += end + 1
                    } else {
                        buffer.append(text[index]); index++
                    }
                }

                rest.startsWith("[") -> {
                    val close = rest.indexOf(']')
                    val openParen = if (close > 0) rest.getOrNull(close + 1) else null
                    if (close > 0 && openParen == '(') {
                        val closeParen = rest.indexOf(')', startIndex = close)
                        if (closeParen > 0) {
                            flush()
                            spans += Span(
                                text = rest.substring(1, close),
                                link = rest.substring(close + 2, closeParen),
                            )
                            index += closeParen + 1
                        } else {
                            buffer.append(text[index]); index++
                        }
                    } else {
                        buffer.append(text[index]); index++
                    }
                }

                else -> {
                    buffer.append(text[index]); index++
                }
            }
        }
        flush()
        return spans.ifEmpty { listOf(Span(text)) }
    }
}
