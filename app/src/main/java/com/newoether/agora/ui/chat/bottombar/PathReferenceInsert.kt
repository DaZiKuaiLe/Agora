package com.newoether.agora.ui.chat.bottombar

import androidx.compose.foundation.text.input.TextFieldState
import com.newoether.agora.util.PathReferenceTokens

/** Inserts a path-reference token at the current selection in the composer. */
internal fun TextFieldState.insertPathReference(absolutePath: String) {
    insertPathReferences(listOf(absolutePath))
}

/** Inserts multiple path-reference tokens in one edit (cursor advances after each). */
internal fun TextFieldState.insertPathReferences(absolutePaths: List<String>) {
    val paths = absolutePaths.map { it.trim() }.filter { it.isNotEmpty() }
    if (paths.isEmpty()) return
    edit {
        var text = toString()
        var sel = selection
        var start = minOf(sel.start, sel.end).coerceIn(0, text.length)
        var end = maxOf(sel.start, sel.end).coerceIn(start, text.length)
        var cursor = end
        for (absolutePath in paths) {
            val insertion = PathReferenceTokens.insertionAt(
                text = text,
                selectionStart = start,
                selectionEnd = end,
                absolutePath = absolutePath,
            )
            text = insertion.newText
            cursor = insertion.cursor
            start = cursor
            end = cursor
        }
        replace(0, length, text)
        placeCursorBeforeCharAt(cursor.coerceIn(0, text.length))
    }
}
