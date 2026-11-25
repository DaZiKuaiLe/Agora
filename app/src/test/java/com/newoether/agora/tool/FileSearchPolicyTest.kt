package com.newoether.agora.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileSearchPolicyTest {

    @Test
    fun finishGrepAndList_pageRemainingVersusScanBudget() {
        data class Case(
            val name: String,
            val total: Int,
            val offset: Int,
            val pageSize: Int,
            val scanReason: String?,
            val remaining: Int,
            val truncated: Boolean,
            val reason: String?,
            val lowerBound: Boolean,
            val incomplete: Boolean,
            val phase: String,
        )
        val cases = listOf(
            Case("exact page", 20, 0, 20, null, 0, false, null, false, false, FileSearchPolicy.PHASE_DONE),
            Case("more than a page", 80, 0, 50, null, 30, true, FileSearchPolicy.REASON_PAGE, false, false, FileSearchPolicy.PHASE_DONE),
            Case("second page", 80, 50, 30, null, 0, false, null, false, false, FileSearchPolicy.PHASE_DONE),
            Case("time budget with extra counted", 12, 0, 10, FileSearchPolicy.REASON_TIME, 2, true, FileSearchPolicy.REASON_TIME, true, true, FileSearchPolicy.PHASE_SCAN),
            Case("file cap before filling page", 3, 0, 50, FileSearchPolicy.REASON_FILE, 0, true, FileSearchPolicy.REASON_FILE, true, true, FileSearchPolicy.PHASE_SCAN),
        )
        cases.forEach { case ->
            val page = List(case.pageSize.coerceAtMost((case.total - case.offset).coerceAtLeast(0))) {
                FileGrepMatch("a.kt", it + 1, "x")
            }
            val grep = FileSearchPolicy.finishGrep(
                pattern = "x",
                page = page,
                totalMatches = case.total,
                offset = case.offset,
                scannedFiles = 1,
                skippedOversize = 0,
                skippedBinary = 0,
                ignoredDirs = emptyList(),
                scanReason = case.scanReason,
            )
            assertEquals(case.name, case.remaining, grep.remaining)
            assertEquals(case.name, case.truncated, grep.truncated)
            assertEquals(case.name, case.reason, grep.truncatedReason)
            assertEquals(case.name, case.lowerBound, grep.remainingIsLowerBound)
            assertEquals(case.name, case.incomplete, grep.incomplete)
            assertEquals(case.name, case.phase, grep.phase)

            val listPage = List(page.size) {
                FileListEntry("f$it", FileSearchPolicy.TYPE_FILE, size = 1, mtimeMs = 0)
            }
            val list = FileSearchPolicy.finishList(
                page = listPage,
                totalMatched = case.total,
                offset = case.offset,
                scanReason = case.scanReason,
            )
            assertEquals(case.name, case.remaining, list.remaining)
            assertEquals(case.name, case.truncated, list.truncated)
            assertEquals(case.name, case.reason, list.truncatedReason)
        }
    }

    @Test
    fun finishGrep_emptyTimeBudgetIsWalkAndHasNote() {
        val grep = FileSearchPolicy.finishGrep(
            pattern = "x",
            page = emptyList(),
            totalMatches = 0,
            offset = 0,
            scannedFiles = 0,
            skippedOversize = 0,
            skippedBinary = 0,
            ignoredDirs = emptyList(),
            scanReason = FileSearchPolicy.REASON_TIME,
        )
        assertTrue(grep.incomplete)
        assertEquals(FileSearchPolicy.PHASE_WALK, grep.phase)
        assertEquals(FileSearchPolicy.INCOMPLETE_NOTE, grep.note)
    }

    @Test
    fun sortListEntries_dirsBeforeFiles() {
        val sorted = FileSearchPolicy.sortListEntries(
            listOf(
                FileListEntry("/a/z.txt", FileSearchPolicy.TYPE_FILE),
                FileListEntry("/a/src", FileSearchPolicy.TYPE_DIR),
                FileListEntry("/a/b.txt", FileSearchPolicy.TYPE_FILE),
            ),
        )
        assertEquals(listOf("/a/src", "/a/b.txt", "/a/z.txt"), sorted.map { it.path })
    }
}
