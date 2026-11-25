package com.newoether.agora.util

/**
 * Opaque path-reference markers for the chat composer and user-message history.
 * Stored as full-path tokens in Room / drafts; rendered as filename capsules in the UI;
 * expanded to absolute (shell-safe) paths when projecting to the model and in tool panels.
 */
object PathReferenceTokens {
    private const val PREFIX = "⟦agora-path:"
    private const val SUFFIX = "⟧"
    private const val DISPLAY_PAD = '\u00A0'

    /** Max visible characters for the composer capsule (ellipsis inclusive). */
    internal const val MAX_DISPLAY_NAME_CHARS = 16

    private val TOKEN_REGEX = Regex(
        Regex.escape(PREFIX) + "(.+?)" + Regex.escape(SUFFIX),
    )

    data class Match(
        val start: Int,
        val end: Int,
        val path: String,
    ) {
        val raw: String get() = textOf(path)
        val displayName: String get() = displayName(path)
        val displayLabel: String get() = "$DISPLAY_PAD$displayName$DISPLAY_PAD"
    }

    fun wrap(absolutePath: String): String = textOf(absolutePath)

    fun textOf(absolutePath: String): String = "$PREFIX$absolutePath$SUFFIX"

    fun displayName(path: String): String {
        val name = path.substringAfterLast('/').substringAfterLast('\\')
        val base = name.ifBlank { path }
        return ellipsizeDisplayName(base, MAX_DISPLAY_NAME_CHARS)
    }

    /**
     * Keeps the capsule narrow: truncate with an end ellipsis.
     * When a short file extension exists, prefer preserving it (`long-na….txt`).
     */
    internal fun ellipsizeDisplayName(name: String, maxChars: Int): String {
        if (maxChars <= 1) return "…"
        if (name.length <= maxChars) return name
        val dot = name.lastIndexOf('.')
        val ext = if (dot > 0 && dot < name.lastIndex) name.substring(dot) else ""
        // Only keep the extension when it leaves room for a meaningful stem + ellipsis.
        if (ext.isNotEmpty() && ext.length + 2 < maxChars && ext.length <= 6) {
            val stemBudget = maxChars - ext.length - 1
            return name.take(stemBudget) + "…" + ext
        }
        return name.take(maxChars - 1) + "…"
    }

    fun findMatches(text: String): List<Match> =
        TOKEN_REGEX.findAll(text).map { match ->
            Match(
                start = match.range.first,
                end = match.range.last + 1,
                path = match.groupValues[1],
            )
        }.toList()

    fun expandForSend(text: String): String =
        TOKEN_REGEX.replace(text) { match ->
            shellQuote(match.groupValues[1])
        }

    /** Replace tokens with capsule labels (no styling). */
    fun toDisplayText(text: String): String {
        if (!containsToken(text)) return text
        val matches = findMatches(text)
        if (matches.isEmpty()) return text
        return buildString(text.length) {
            var cursor = 0
            for (match in matches) {
                append(text, cursor, match.start)
                append(match.displayLabel)
                cursor = match.end
            }
            if (cursor < text.length) append(text, cursor, text.length)
        }
    }

    /**
     * Builds an annotated display string where path tokens become filename capsules.
     * Also returns a mapper from source-text ranges to display-text ranges for search.
     */
    fun displayProjection(text: String): DisplayProjection {
        val matches = findMatches(text)
        if (matches.isEmpty()) {
            return DisplayProjection(
                sourceText = text,
                displayText = text,
                capsuleRanges = emptyList(),
                sourceToDisplay = { start, end -> start until end },
            )
        }
        data class Seg(
            val sourceStart: Int,
            val sourceEnd: Int,
            val displayStart: Int,
            val displayEnd: Int,
            val isCapsule: Boolean,
        )
        val segs = ArrayList<Seg>()
        val display = StringBuilder()
        var cursor = 0
        for (match in matches) {
            if (cursor < match.start) {
                val d0 = display.length
                display.append(text, cursor, match.start)
                segs += Seg(cursor, match.start, d0, display.length, isCapsule = false)
            }
            val d0 = display.length
            display.append(match.displayLabel)
            segs += Seg(match.start, match.end, d0, display.length, isCapsule = true)
            cursor = match.end
        }
        if (cursor < text.length) {
            val d0 = display.length
            display.append(text, cursor, text.length)
            segs += Seg(cursor, text.length, d0, display.length, isCapsule = false)
        }
        val displayText = display.toString()
        val capsuleRanges = segs.filter { it.isCapsule }.map { it.displayStart until it.displayEnd }
        return DisplayProjection(
            sourceText = text,
            displayText = displayText,
            capsuleRanges = capsuleRanges,
            sourceToDisplay = { start, end ->
                val s = start.coerceIn(0, text.length)
                val e = end.coerceIn(s, text.length)
                if (s == e) {
                    val at = segs.firstOrNull { s in it.sourceStart until it.sourceEnd }
                        ?: segs.lastOrNull { it.sourceEnd == s }
                    val idx = at?.displayStart ?: displayText.length
                    return@DisplayProjection idx until idx
                }
                var dStart = Int.MAX_VALUE
                var dEnd = Int.MIN_VALUE
                for (seg in segs) {
                    if (s < seg.sourceEnd && e > seg.sourceStart) {
                        dStart = minOf(dStart, seg.displayStart)
                        dEnd = maxOf(dEnd, seg.displayEnd)
                    }
                }
                if (dStart == Int.MAX_VALUE) null else dStart until dEnd
            },
        )
    }

    data class DisplayProjection(
        val sourceText: String,
        val displayText: String,
        val capsuleRanges: List<IntRange>,
        val sourceToDisplay: (start: Int, end: Int) -> IntRange?,
    )

    fun containsToken(text: String): Boolean = TOKEN_REGEX.containsMatchIn(text)

    fun extractPaths(text: String): List<String> =
        TOKEN_REGEX.findAll(text).map { it.groupValues[1] }.toList()

    fun shellQuote(path: String): String {
        if (path.isEmpty()) return "''"
        val needsQuote = path.any { ch ->
            ch.isWhitespace() || ch == '"' || ch == '\'' || ch == '$' ||
                ch == '`' || ch == '\\' || ch == ';' || ch == '|' || ch == '&' ||
                ch == '<' || ch == '>' || ch == '(' || ch == ')'
        }
        if (!needsQuote) return path
        return "'" + path.replace("'", "'\\''") + "'"
    }

    /**
     * Builds the insertion string and new cursor offset after replacing [selectionStart, selectionEnd).
     */
    fun insertionAt(
        text: String,
        selectionStart: Int,
        selectionEnd: Int,
        absolutePath: String,
    ): Insertion {
        val start = selectionStart.coerceIn(0, text.length)
        val end = selectionEnd.coerceIn(start, text.length)
        val token = wrap(absolutePath)
        val needLeadingSpace = start > 0 && !text[start - 1].isWhitespace()
        val needTrailingSpace = end < text.length && !text[end].isWhitespace()
        val insertion = buildString {
            if (needLeadingSpace) append(' ')
            append(token)
            if (needTrailingSpace) append(' ')
        }
        val newText = text.replaceRange(start, end, insertion)
        val cursor = start + insertion.length
        return Insertion(newText = newText, cursor = cursor, inserted = insertion)
    }

    /**
     * If a deletion/replacement only partially covers one or more path tokens, expand the edit
     * range so those tokens are removed atomically (Cursor-style chip delete).
     *
     * @return expanded `[start, end)` in [original] coordinates, or null if no expansion needed.
     */
    fun expandedEditRange(
        original: String,
        editStart: Int,
        editEnd: Int,
    ): IntRange? {
        val start = editStart.coerceIn(0, original.length)
        val end = editEnd.coerceIn(start, original.length)
        val matches = findMatches(original)
        if (matches.isEmpty()) return null
        val partial = matches.filter { match ->
            val intersects = start < match.end && end > match.start
            if (!intersects) return@filter false
            val fullyCovered = start <= match.start && end >= match.end
            !fullyCovered
        }
        if (partial.isEmpty()) return null
        val expandedStart = minOf(start, partial.minOf { it.start })
        val expandedEnd = maxOf(end, partial.maxOf { it.end })
        if (expandedStart == start && expandedEnd == end) return null
        return expandedStart until expandedEnd
    }

    data class Insertion(
        val newText: String,
        val cursor: Int,
        val inserted: String,
    )
}
