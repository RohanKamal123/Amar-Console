package com.amarhelper.console.ui.chat

/**
 * Recognises and parses unified diffs.
 *
 * Agents emit file edits as diffs constantly, and rendering them as plain text loses the
 * one thing that makes a diff readable — which side each line is on.
 */
object DiffParser {

    private val hunkHeader = Regex("""^@@\s+-\d+(,\d+)?\s+\+\d+(,\d+)?\s+@@""")

    /**
     * True when the text is a unified diff. Requires a hunk header or a `---`/`+++`
     * file-header pair, so ordinary prose containing a leading `-` bullet is not
     * mistaken for one.
     */
    fun looksLikeDiff(text: String): Boolean {
        val lines = text.lineSequence().take(40).toList()
        if (lines.any { hunkHeader.containsMatchIn(it) }) return true
        val hasMinusHeader = lines.any { it.startsWith("--- ") }
        val hasPlusHeader = lines.any { it.startsWith("+++ ") }
        return hasMinusHeader && hasPlusHeader
    }

    fun parse(text: String): List<DiffLine> = text.replace("\r\n", "\n").split('\n').map { line ->
        when {
            hunkHeader.containsMatchIn(line) -> DiffLine(DiffLine.Kind.HunkHeader, line)
            line.startsWith("+++ ") || line.startsWith("--- ") ->
                DiffLine(DiffLine.Kind.FileHeader, line)
            line.startsWith("diff --git") || line.startsWith("index ") ->
                DiffLine(DiffLine.Kind.FileHeader, line)
            line.startsWith("+") -> DiffLine(DiffLine.Kind.Added, line.drop(1))
            line.startsWith("-") -> DiffLine(DiffLine.Kind.Removed, line.drop(1))
            else -> DiffLine(DiffLine.Kind.Context, line.removePrefix(" "))
        }
    }

    /** Counts for the summary line on a collapsed diff. */
    fun summarize(lines: List<DiffLine>): Pair<Int, Int> = Pair(
        lines.count { it.kind == DiffLine.Kind.Added },
        lines.count { it.kind == DiffLine.Kind.Removed },
    )
}
