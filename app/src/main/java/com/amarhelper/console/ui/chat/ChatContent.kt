package com.amarhelper.console.ui.chat

/**
 * The rendered form of a chat message: a list of blocks, produced by [MarkdownParser].
 *
 * This model is deliberately free of Compose types so parsing can be unit-tested on the
 * JVM, and so the same content can be rendered differently (a tool card's output is
 * styled differently from an agent's prose) without re-parsing.
 */
sealed interface ChatBlock {

    data class Paragraph(val spans: List<Span>) : ChatBlock

    data class Heading(val level: Int, val spans: List<Span>) : ChatBlock

    data class BulletList(val items: List<ListItem>) : ChatBlock

    data class NumberedList(val items: List<ListItem>) : ChatBlock

    /** A fenced or indented code block, pre-tokenized for highlighting. */
    data class Code(
        val language: String?,
        val lines: List<List<Token>>,
        val raw: String,
    ) : ChatBlock

    /** A unified diff, recognised either by a ```diff fence or by its own headers. */
    data class Diff(val lines: List<DiffLine>) : ChatBlock

    data class Quote(val blocks: List<ChatBlock>) : ChatBlock

    data object Divider : ChatBlock
}

data class ListItem(val spans: List<Span>, val depth: Int = 0)

/** An inline run of text with its formatting. */
data class Span(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val link: String? = null,
)

/** One line of a unified diff. */
data class DiffLine(val kind: Kind, val text: String) {
    enum class Kind {
        /** `--- a/file` / `+++ b/file` */
        FileHeader,

        /** `@@ -1,4 +1,6 @@` */
        HunkHeader,
        Added,
        Removed,
        Context,
    }
}

/** A syntax-highlighted run within a line of code. */
data class Token(val text: String, val type: Type) {
    enum class Type { Plain, Keyword, StringLiteral, Number, Comment, Function, Punctuation }
}
