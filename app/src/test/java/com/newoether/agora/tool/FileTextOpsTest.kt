package com.newoether.agora.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileTextOpsTest {

    @Test
    fun applyReplace_table() {
        data class Case(
            val name: String,
            val text: String,
            val old: String,
            val new: String,
            val replaceAll: Boolean,
            val expected: FileTextOps.ReplaceResult,
        )
        val cases = listOf(
            Case(
                name = "unique hunk",
                text = "aaa\nbbb\nccc\n",
                old = "bbb",
                new = "ddd",
                replaceAll = false,
                expected = FileTextOps.ReplaceResult.Applied("aaa\nddd\nccc\n", 1),
            ),
            Case(
                name = "ambiguous without replace_all",
                text = "x\nfoo\ny\nfoo\n",
                old = "foo",
                new = "bar",
                replaceAll = false,
                expected = FileTextOps.ReplaceResult.Ambiguous(2, listOf(2, 4)),
            ),
            Case(
                name = "replace_all",
                text = "x\nfoo\ny\nfoo\n",
                old = "foo",
                new = "bar",
                replaceAll = true,
                expected = FileTextOps.ReplaceResult.Applied("x\nbar\ny\nbar\n", 2),
            ),
            Case(
                name = "not found with nearby first-line hint",
                text = "alpha\nbeta needle\ngamma\n",
                old = "needle\nmore",
                new = "x",
                replaceAll = false,
                expected = FileTextOps.ReplaceResult.NotFound(listOf(2)),
            ),
            Case(
                name = "empty old",
                text = "abc",
                old = "",
                new = "x",
                replaceAll = false,
                expected = FileTextOps.ReplaceResult.EmptyOld,
            ),
            Case(
                name = "CRLF file accepts LF hunk and keeps CRLF",
                text = "aaa\r\nbbb\r\nccc\r\n",
                old = "bbb\nccc",
                new = "ddd\neee",
                replaceAll = false,
                expected = FileTextOps.ReplaceResult.Applied("aaa\r\nddd\r\neee\r\n", 1),
            ),
            Case(
                name = "LF file accepts CRLF hunk and keeps LF",
                text = "aaa\nbbb\nccc\n",
                old = "bbb\r\nccc",
                new = "ddd\r\neee",
                replaceAll = false,
                expected = FileTextOps.ReplaceResult.Applied("aaa\nddd\neee\n", 1),
            ),
            Case(
                name = "CRLF replace_all",
                text = "foo\r\nx\r\nfoo\r\n",
                old = "foo",
                new = "bar",
                replaceAll = true,
                expected = FileTextOps.ReplaceResult.Applied("bar\r\nx\r\nbar\r\n", 2),
            ),
        )
        cases.forEach { case ->
            assertEquals(case.name, case.expected, FileTextOps.applyReplace(case.text, case.old, case.new, case.replaceAll))
        }
    }

    @Test
    fun selectLines_table() {
        val text = "a\nb\nc\nd"
        data class Case(val name: String, val start: Int?, val end: Int?, val expected: String, val startOut: Int, val endOut: Int)
        val cases = listOf(
            Case("full", null, null, "a\nb\nc\nd", 1, 4),
            Case("middle", 2, 3, "b\nc", 2, 3),
            Case("past end clamps", 3, 99, "c\nd", 3, 4),
        )
        cases.forEach { case ->
            val slice = FileTextOps.selectLines(text, case.start, case.end)
            assertEquals(case.name, case.expected, slice.text)
            assertEquals(case.name, case.startOut, slice.startLine)
            assertEquals(case.name, case.endOut, slice.endLine)
            assertEquals(case.name, 4, slice.totalLines)
        }
    }

    @Test
    fun exceedsReadCap_usesLineOrByteBudget() {
        assertTrue(FileTextOps.exceedsReadCap(FileTextOps.DEFAULT_MAX_READ_LINES + 1, 10))
        assertTrue(FileTextOps.exceedsReadCap(1, FileTextOps.DEFAULT_MAX_READ_BYTES + 1))
        assertEquals(false, FileTextOps.exceedsReadCap(1, 10))
    }

    @Test
    fun omitUnreadBody_usesStatSizeWithoutRange() {
        data class Case(val name: String, val size: Long, val ranged: Boolean, val omit: Boolean)
        val over = FileTextOps.DEFAULT_MAX_READ_BYTES.toLong() + 1
        val cases = listOf(
            Case("over size no range", over, false, true),
            Case("over size with range", over, true, false),
            Case("at budget no range", FileTextOps.DEFAULT_MAX_READ_BYTES.toLong(), false, false),
            Case("small no range", 10, false, false),
        )
        cases.forEach { case ->
            assertEquals(case.name, case.omit, FileTextOps.omitUnreadBody(case.size, case.ranged))
        }
    }
}
