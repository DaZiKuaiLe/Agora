package com.newoether.agora.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FileSearchRootsTest {

    @Test
    fun of_fileIsSoleTargetDirectoryDefersToListing() {
        data class Case(
            val name: String,
            val path: String,
            val isDirectory: Boolean,
            val expected: List<String>?,
        )
        val cases = listOf(
            Case("blank", "  ", false, null),
            Case("file", "/repo/scene-split.js", false, listOf("/repo/scene-split.js")),
            Case("directory", "/repo/scene-scanner", true, null),
            Case("windows file", "/C:/src/a.js", false, listOf("/C:/src/a.js")),
        )
        cases.forEach { case ->
            assertEquals(case.name, case.expected, FileSearchRoots.of(case.path, case.isDirectory))
        }
    }
}
