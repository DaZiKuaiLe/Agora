package com.newoether.agora.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PathReferenceTokensTest {
    @Test
    fun wrapAndExpandSimplePath() {
        val path = "/storage/emulated/0/Download/a.txt"
        val token = PathReferenceTokens.wrap(path)
        assertTrue(token.contains(path))
        assertEquals(path, PathReferenceTokens.expandForSend(token))
    }

    @Test
    fun expandQuotesPathsWithSpaces() {
        val path = "/storage/emulated/0/My Files/note.txt"
        val text = "See ${PathReferenceTokens.wrap(path)} please"
        assertEquals(
            "See '/storage/emulated/0/My Files/note.txt' please",
            PathReferenceTokens.expandForSend(text),
        )
    }

    @Test
    fun insertionAddsSpacingAroundNeighbors() {
        val insertion = PathReferenceTokens.insertionAt(
            text = "hello world",
            selectionStart = 5,
            selectionEnd = 5,
            absolutePath = "/tmp/a",
        )
        assertEquals("hello ${PathReferenceTokens.wrap("/tmp/a")} world", insertion.newText)
    }

    @Test
    fun extractPathsFindsAllTokens() {
        val a = PathReferenceTokens.wrap("/a")
        val b = PathReferenceTokens.wrap("/b")
        assertEquals(listOf("/a", "/b"), PathReferenceTokens.extractPaths("x ${a} y ${b}"))
        assertFalse(PathReferenceTokens.containsToken("plain text"))
    }

    @Test
    fun shellQuoteEscapesSingleQuotes() {
        assertEquals(
            "'/tmp/it'\\''s.txt'",
            PathReferenceTokens.shellQuote("/tmp/it's.txt"),
        )
    }

    @Test
    fun displayNameUsesBasename() {
        assertEquals(
            "note.txt",
            PathReferenceTokens.displayName("/storage/emulated/0/My Files/note.txt"),
        )
        assertEquals(
            "note.txt",
            PathReferenceTokens.findMatches(
                PathReferenceTokens.wrap("/storage/emulated/0/My Files/note.txt"),
            ).single().displayName,
        )
    }

    @Test
    fun displayNameEllipsizesLongNames() {
        val longName = "very-long-filename-example.kt"
        val shown = PathReferenceTokens.displayName("/tmp/$longName")
        assertTrue(shown.length <= PathReferenceTokens.MAX_DISPLAY_NAME_CHARS)
        assertTrue(shown.endsWith("….kt") || shown.endsWith("…"))
        assertEquals(
            "short.txt",
            PathReferenceTokens.ellipsizeDisplayName("short.txt", 16),
        )
        assertEquals(
            "abcdefghijklmno…",
            PathReferenceTokens.ellipsizeDisplayName("abcdefghijklmnopqrstuvwxyz", 16),
        )
        assertEquals(
            "very-long-fi….kt",
            PathReferenceTokens.ellipsizeDisplayName("very-long-filename.kt", 16),
        )
    }

    @Test
    fun expandedEditRangeGrowsPartialTokenDeletion() {
        val token = PathReferenceTokens.wrap("/tmp/a.txt")
        val text = "before ${token} after"
        val tokenStart = text.indexOf(token)
        val partialStart = tokenStart + 3
        val partialEnd = partialStart + 1
        val expanded = PathReferenceTokens.expandedEditRange(text, partialStart, partialEnd)
        assertEquals(tokenStart until (tokenStart + token.length), expanded)
    }

    @Test
    fun expandedEditRangeNullWhenFullTokenAlreadyDeleted() {
        val token = PathReferenceTokens.wrap("/tmp/a.txt")
        val text = "x${token}y"
        val tokenStart = text.indexOf(token)
        assertNull(
            PathReferenceTokens.expandedEditRange(
                text,
                tokenStart,
                tokenStart + token.length,
            ),
        )
    }

    @Test
    fun findMatchesReportsRanges() {
        val token = PathReferenceTokens.wrap("/a")
        val text = "hi $token!"
        val match = PathReferenceTokens.findMatches(text).single()
        assertEquals(token, text.substring(match.start, match.end))
        assertEquals("/a", match.path)
        assertTrue(match.displayLabel.contains("a"))
    }

    @Test
    fun displayProjectionMapsSourceRangesOntoCapsules() {
        val token = PathReferenceTokens.wrap("/tmp/note.txt")
        val text = "see $token please"
        val projection = PathReferenceTokens.displayProjection(text)
        assertFalse(projection.displayText.contains("agora-path"))
        assertTrue(projection.displayText.contains("note.txt"))
        val tokenStart = text.indexOf(token)
        val mapped = projection.sourceToDisplay(tokenStart, tokenStart + token.length)
        assertEquals(projection.capsuleRanges.single(), mapped)
        assertEquals(
            PathReferenceTokens.toDisplayText(text),
            projection.displayText,
        )
    }

    @Test
    fun displayProjectionCapsuleRangesStayWithinDisplayText() {
        val paths = listOf(
            "/storage/emulated/0/DCIM/Camera/IMG_0001.jpg",
            "/storage/emulated/0/DCIM/Camera/IMG_0002.jpg",
            "/storage/emulated/0/Pictures/Screenshots/shot.png",
        )
        var text = ""
        var cursor = 0
        for (path in paths) {
            val insertion = PathReferenceTokens.insertionAt(text, cursor, cursor, path)
            text = insertion.newText
            cursor = insertion.cursor
        }
        val projection = PathReferenceTokens.displayProjection(text)
        assertEquals(paths.size, projection.capsuleRanges.size)
        for (range in projection.capsuleRanges) {
            assertTrue(range.first >= 0)
            assertTrue(range.last + 1 <= projection.displayText.length)
            assertTrue(range.first <= range.last)
        }
        // Display text is much shorter than stored token text (the crash case).
        assertTrue(projection.displayText.length < text.length)
    }

    @Test
    fun toDisplayTextReplacesTokensWithLabels() {
        val token = PathReferenceTokens.wrap("/tmp/a.txt")
        val label = PathReferenceTokens.findMatches(token).single().displayLabel
        assertEquals("x ${label} y", PathReferenceTokens.toDisplayText("x $token y"))
    }
}
