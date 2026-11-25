package com.newoether.agora.ui.chat.bottombar

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.forEachChange
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import com.newoether.agora.util.PathReferenceTokens

/**
 * Renders path-reference tokens as compact filename capsules (background chip + short label)
 * without changing the underlying [androidx.compose.foundation.text.input.TextFieldState] text.
 */
internal class PathReferenceOutputTransformation(
    private val background: Color,
    private val foreground: Color,
) : OutputTransformation {
    override fun TextFieldBuffer.transformOutput() {
        val original = toString()
        val matches = PathReferenceTokens.findMatches(original)
        if (matches.isEmpty()) return
        // Replace each token in place (end → start). A single replace(0, length, …)
        // collapses offset mapping so one visual backspace can wipe the whole field.
        for (match in matches.asReversed()) {
            replace(match.start, match.end, match.displayLabel)
        }
        // Style only after all replaces so ranges match the final display text.
        val style = SpanStyle(
            background = background,
            color = foreground,
            fontWeight = FontWeight.Medium,
        )
        val projection = PathReferenceTokens.displayProjection(original)
        for (range in projection.capsuleRanges) {
            val endExclusive = range.last + 1
            if (range.first in 0 until length && endExclusive in range.first..length) {
                addStyle(style, range.first, endExclusive)
            }
        }
    }
}

/**
 * Deletes / replaces a path-reference token as a single unit when the user edits inside it.
 */
@OptIn(ExperimentalFoundationApi::class)
internal object PathReferenceInputTransformation : InputTransformation {
    override fun TextFieldBuffer.transformInput() {
        if (changes.changeCount == 0) return
        val original = originalText.toString()
        if (!PathReferenceTokens.containsToken(original)) return

        // Common case: one keyboard/IME change — expand partial token edits atomically.
        if (changes.changeCount == 1) {
            val range = changes.getRange(0)
            val originalRange = changes.getOriginalRange(0)
            val expanded = PathReferenceTokens.expandedEditRange(
                original = original,
                editStart = originalRange.start,
                editEnd = originalRange.end,
            ) ?: return
            val inserted = if (range.length > 0) {
                asStringInRange(range.start, range.end)
            } else {
                ""
            }
            revertAllChanges()
            replace(expanded.first, expanded.last + 1, inserted)
            placeCursorBeforeCharAt(
                (expanded.first + inserted.length).coerceIn(0, length),
            )
            return
        }

        // Multi-change fallback: drop only tokens touched by this edit that are no longer intact.
        val touched = ArrayList<Pair<Int, Int>>(changes.changeCount)
        changes.forEachChange { _, originalRange ->
            touched += originalRange.start to originalRange.end
        }
        val proposed = toString()
        val damaged = PathReferenceTokens.findMatches(original).filter { match ->
            val hit = touched.any { (editStart, editEnd) ->
                if (editStart == editEnd) {
                    editStart in match.start until match.end
                } else {
                    editStart < match.end && editEnd > match.start
                }
            }
            hit && !proposed.contains(match.raw)
        }
        if (damaged.isEmpty()) return
        var fixed = original
        for (match in damaged.sortedByDescending { it.start }) {
            fixed = fixed.removeRange(match.start, match.end)
        }
        if (fixed != proposed) {
            replace(0, length, fixed)
            placeCursorBeforeCharAt(fixed.length.coerceAtMost(length))
        }
    }
}

/** Safe substring for a still-valid buffer range (avoids throwing on empty). */
private fun TextFieldBuffer.asStringInRange(start: Int, end: Int): String {
    if (start >= end) return ""
    return toString().substring(start, end.coerceAtMost(length))
}

@Composable
internal fun rememberPathReferenceOutputTransformation(
    background: Color,
    foreground: Color,
): PathReferenceOutputTransformation =
    remember(background, foreground) {
        PathReferenceOutputTransformation(background, foreground)
    }
