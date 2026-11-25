package com.newoether.agora.tool

/**
 * Pure text operations for file tools. Line numbers are 1-based and inclusive.
 */
object FileTextOps {
    const val DEFAULT_MAX_READ_LINES = 200
    const val DEFAULT_MAX_READ_BYTES = 100_000
    const val MAX_GREP_MATCHES = 50
    const val MAX_GREP_FILES = 200
    const val MAX_GREP_FILE_BYTES = 500_000L
    const val MAX_LIST_FILES = 200
    const val MAX_REPLACE_BYTES = 1_000_000
    const val MAX_WRITE_BYTES = 2_000_000
    const val MAX_LINE_HINTS = 5
    const val GREP_LINE_CHARS = 500

    /** Model-facing limit copy. Keep in sync with the constants above. */
    val READ_LIMITS =
        "Limits: without start_line/end_line, a file over $DEFAULT_MAX_READ_BYTES bytes returns metadata_only " +
            "(size only; no body download and no line_count). " +
            "A file at or under that size but over $DEFAULT_MAX_READ_LINES lines also returns metadata_only " +
            "(size + line_count). A ranged read is capped at $DEFAULT_MAX_READ_BYTES bytes. Binary files fail. " +
            "metadata_only is not an error — pass a smaller start_line/end_line range."
    val GREP_LIMITS =
        "Returns at most $MAX_GREP_MATCHES matches per call; pass offset for the next page " +
            "(remaining is how many more were counted). Scans at most $MAX_GREP_FILES files; " +
            "files over $MAX_GREP_FILE_BYTES bytes are skipped and counted. Recursive. " +
            "Skips noise dirs (node_modules, .git, Cache, …) unless no_ignore is true. " +
            "If incomplete is true, empty matches do not mean the pattern is absent."
    val GLOB_LIMITS =
        "Returns at most $MAX_LIST_FILES paths per call; pass offset for the next page. " +
            "Recursive name search. * is one segment; ** crosses directories; a pattern with no / " +
            "matches that basename at any depth (*.kt means **/*.kt). " +
            "Skips noise dirs unless no_ignore is true. " +
            "If incomplete is true, empty paths do not mean nothing exists."
    val LIST_LIMITS =
        "Lists one directory (files and subfolders). Directories are listed first. " +
            "Returns at most $MAX_LIST_FILES entries per call; pass offset for the next page. " +
            "dirs_only=true returns subfolders only. Each entry has type, size, and mtime_ms."
    val REPLACE_LIMITS =
        "Limits: the target file must be at most $MAX_REPLACE_BYTES bytes or the call fails without writing. " +
            "Success returns path and occurrences only, not the new file body."
    val WRITE_LIMITS =
        "Limits: content must be at most $MAX_WRITE_BYTES bytes. Success returns path and bytes_written, not the file body."
    val FILE_LIMITS_SUMMARY =
        "File-tool caps: read body $DEFAULT_MAX_READ_LINES lines or $DEFAULT_MAX_READ_BYTES bytes; " +
            "over $DEFAULT_MAX_READ_BYTES bytes without a range is metadata_only (size only). " +
            "grep $MAX_GREP_MATCHES matches/page / $MAX_GREP_FILES files / skip files over $MAX_GREP_FILE_BYTES bytes; " +
            "list/glob $MAX_LIST_FILES paths/page; str_replace file ≤ $MAX_REPLACE_BYTES bytes; write content ≤ $MAX_WRITE_BYTES bytes."

    data class LineSlice(
        val text: String,
        val startLine: Int,
        val endLine: Int,
        val totalLines: Int,
    )

    sealed class ReplaceResult {
        data class Applied(val text: String, val occurrences: Int) : ReplaceResult()
        data class Ambiguous(val occurrences: Int, val lineNumbers: List<Int>) : ReplaceResult()
        data class NotFound(val lineHints: List<Int>) : ReplaceResult()
        data object EmptyOld : ReplaceResult()
    }

    fun countLines(text: String): Int {
        if (text.isEmpty()) return 0
        var lines = 1
        for (ch in text) if (ch == '\n') lines++
        return lines
    }

    fun lineNumberAt(text: String, index: Int): Int {
        var line = 1
        val end = index.coerceIn(0, text.length)
        for (i in 0 until end) if (text[i] == '\n') line++
        return line
    }

    fun occurrenceLineNumbers(text: String, old: String): List<Int> =
        findNewlineInsensitiveSpans(text, old).map { lineNumberAt(text, it.start) }

    data class TextSpan(val start: Int, val end: Int)

    /** Treats CRLF, LF, and CR as the same line break when locating [old]. */
    fun findNewlineInsensitiveSpans(text: String, old: String): List<TextSpan> {
        if (old.isEmpty()) return emptyList()
        val needle = normalizeNewlines(old)
        val spans = mutableListOf<TextSpan>()
        var from = 0
        while (from < text.length) {
            val span = indexOfNewlineInsensitive(text, needle, from) ?: break
            spans.add(span)
            from = span.end
        }
        return spans
    }

    fun normalizeNewlines(text: String): String =
        text.replace("\r\n", "\n").replace("\r", "\n")

    fun newlineStyle(sample: String): String? = when {
        sample.contains("\r\n") -> "\r\n"
        sample.contains('\r') -> "\r"
        sample.contains('\n') -> "\n"
        else -> null
    }

    fun adaptNewlines(replacement: String, sample: String, file: String): String {
        val normalized = normalizeNewlines(replacement)
        if (!normalized.contains('\n')) return normalized
        val style = newlineStyle(sample) ?: newlineStyle(file) ?: "\n"
        return normalized.replace("\n", style)
    }

    fun selectLines(text: String, startLine: Int?, endLine: Int?): LineSlice {
        val total = countLines(text)
        if (total == 0) {
            return LineSlice("", 1, 0, 0)
        }
        val start = (startLine ?: 1).coerceAtLeast(1)
        val end = (endLine ?: total).coerceAtLeast(start)
        if (start > total) {
            return LineSlice("", start, start, total)
        }
        val clampedEnd = end.coerceAtMost(total)
        val lines = text.split('\n', limit = clampedEnd + 1)
        val slice = lines.subList(start - 1, clampedEnd).joinToString("\n")
        return LineSlice(slice, start, clampedEnd, total)
    }

    fun applyReplace(text: String, old: String, new: String, replaceAll: Boolean): ReplaceResult {
        if (old.isEmpty()) return ReplaceResult.EmptyOld
        val spans = findNewlineInsensitiveSpans(text, old)
        if (spans.isEmpty()) return ReplaceResult.NotFound(missingHints(text, old))
        val lines = spans.map { lineNumberAt(text, it.start) }
        if (!replaceAll && spans.size != 1) {
            return ReplaceResult.Ambiguous(spans.size, lines.take(MAX_LINE_HINTS))
        }
        val next = StringBuilder(text)
        for (span in spans.asReversed()) {
            val sample = text.substring(span.start, span.end)
            next.replace(span.start, span.end, adaptNewlines(new, sample, text))
        }
        return ReplaceResult.Applied(next.toString(), spans.size)
    }

    fun missingHints(text: String, old: String): List<Int> {
        val needle = normalizeNewlines(old).lineSequence().firstOrNull()?.takeIf { it.isNotBlank() }
            ?: return emptyList()
        val hints = mutableListOf<Int>()
        text.lineSequence().forEachIndexed { index, line ->
            if (hints.size >= MAX_LINE_HINTS) return hints
            if (line.contains(needle)) hints.add(index + 1)
        }
        return hints
    }

    private fun indexOfNewlineInsensitive(haystack: String, needle: String, from: Int): TextSpan? {
        var start = from
        while (start < haystack.length) {
            var hi = start
            var ni = 0
            var matched = true
            while (ni < needle.length) {
                if (hi >= haystack.length) {
                    matched = false
                    break
                }
                val nch = needle[ni]
                if (nch == '\n') {
                    when {
                        haystack.startsWith("\r\n", hi) -> hi += 2
                        haystack[hi] == '\n' || haystack[hi] == '\r' -> hi += 1
                        else -> {
                            matched = false
                            break
                        }
                    }
                    ni++
                } else if (haystack[hi] != nch) {
                    matched = false
                    break
                } else {
                    hi++
                    ni++
                }
            }
            if (matched) return TextSpan(start, hi)
            start++
        }
        return null
    }

    fun compilePattern(raw: String): Regex = try {
        Regex(raw)
    } catch (_: Exception) {
        Regex(Regex.escape(raw))
    }

    fun exceedsReadCap(lineCount: Int, byteCount: Int): Boolean =
        lineCount > DEFAULT_MAX_READ_LINES || byteCount > DEFAULT_MAX_READ_BYTES

    /** No range + size already over the read-body budget: return metadata, do not download or slurp. */
    fun omitUnreadBody(size: Long, ranged: Boolean): Boolean =
        !ranged && size > DEFAULT_MAX_READ_BYTES.toLong()
}
