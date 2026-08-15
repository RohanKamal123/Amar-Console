package com.amarhelper.console.ui.chat

/**
 * A deliberately small syntax highlighter.
 *
 * It tokenizes one line at a time into comments, strings, numbers, keywords and
 * everything else — enough to make code readable on a phone without pulling in a
 * grammar-based highlighter and its language packs. Unknown languages fall back to
 * strings, numbers and comments, which are common to nearly everything.
 */
object SyntaxHighlighter {

    private val kotlinLike = setOf(
        "fun", "val", "var", "class", "object", "interface", "data", "sealed", "when",
        "if", "else", "for", "while", "return", "import", "package", "private", "public",
        "internal", "override", "suspend", "companion", "null", "true", "false", "in",
        "is", "as", "try", "catch", "finally", "throw", "this", "super", "typealias",
    )
    private val pythonLike = setOf(
        "def", "class", "import", "from", "return", "if", "elif", "else", "for", "while",
        "try", "except", "finally", "with", "as", "pass", "raise", "yield", "lambda",
        "None", "True", "False", "and", "or", "not", "in", "is", "async", "await", "self",
    )
    private val jsLike = setOf(
        "function", "const", "let", "var", "class", "return", "if", "else", "for",
        "while", "import", "export", "from", "default", "async", "await", "new", "this",
        "typeof", "null", "undefined", "true", "false", "try", "catch", "finally",
    )
    private val shellLike = setOf(
        "if", "then", "fi", "for", "do", "done", "while", "case", "esac", "function",
        "echo", "export", "cd", "sudo", "curl", "docker", "git", "grep", "cat", "ls",
    )

    private fun keywordsFor(language: String?): Set<String> = when (language?.lowercase()) {
        "kotlin", "kt", "java", "kts" -> kotlinLike
        "python", "py" -> pythonLike
        "javascript", "js", "typescript", "ts", "tsx", "jsx" -> jsLike
        "bash", "sh", "shell", "zsh", "console" -> shellLike
        else -> emptySet()
    }

    private fun commentPrefixFor(language: String?): List<String> = when (language?.lowercase()) {
        "python", "py", "bash", "sh", "shell", "zsh", "yaml", "yml", "toml", "ini" -> listOf("#")
        "sql" -> listOf("--")
        else -> listOf("//", "#")
    }

    private val identifier = Regex("""[A-Za-z_][A-Za-z0-9_]*""")
    private val number = Regex("""\b\d[\d_.]*[fFlLdD]?\b""")

    fun tokenize(line: String, language: String? = null): List<Token> {
        if (line.isEmpty()) return listOf(Token("", Token.Type.Plain))

        val keywords = keywordsFor(language)
        val commentPrefixes = commentPrefixFor(language)
        val tokens = mutableListOf<Token>()
        var index = 0
        val buffer = StringBuilder()

        fun flushPlain() {
            if (buffer.isEmpty()) return
            // Split the buffered run so keywords and numbers can be coloured.
            var last = 0
            val text = buffer.toString()
            identifier.findAll(text).forEach { match ->
                if (match.value in keywords) {
                    if (match.range.first > last) {
                        tokens += Token(text.substring(last, match.range.first), Token.Type.Plain)
                    }
                    tokens += Token(match.value, Token.Type.Keyword)
                    last = match.range.last + 1
                }
            }
            if (last < text.length) {
                val tail = text.substring(last)
                var tailIndex = 0
                number.findAll(tail).forEach { match ->
                    if (match.range.first > tailIndex) {
                        tokens += Token(tail.substring(tailIndex, match.range.first), Token.Type.Plain)
                    }
                    tokens += Token(match.value, Token.Type.Number)
                    tailIndex = match.range.last + 1
                }
                if (tailIndex < tail.length) tokens += Token(tail.substring(tailIndex), Token.Type.Plain)
            }
            buffer.clear()
        }

        while (index < line.length) {
            val rest = line.substring(index)
            val comment = commentPrefixes.firstOrNull { rest.startsWith(it) }
            when {
                comment != null -> {
                    flushPlain()
                    tokens += Token(rest, Token.Type.Comment)
                    index = line.length
                }

                rest.startsWith("\"") || rest.startsWith("'") -> {
                    val quote = rest[0]
                    val end = findStringEnd(rest, quote)
                    flushPlain()
                    if (end > 0) {
                        tokens += Token(rest.substring(0, end + 1), Token.Type.StringLiteral)
                        index += end + 1
                    } else {
                        tokens += Token(rest, Token.Type.StringLiteral)
                        index = line.length
                    }
                }

                else -> {
                    buffer.append(line[index])
                    index++
                }
            }
        }
        flushPlain()
        return tokens.ifEmpty { listOf(Token(line, Token.Type.Plain)) }
    }

    private fun findStringEnd(text: String, quote: Char): Int {
        var i = 1
        while (i < text.length) {
            when {
                text[i] == '\\' -> i += 2
                text[i] == quote -> return i
                else -> i++
            }
        }
        return -1
    }
}
