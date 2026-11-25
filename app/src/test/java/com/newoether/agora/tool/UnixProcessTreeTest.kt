package com.newoether.agora.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UnixProcessTreeTest {
    @Test
    fun descendantPidsFollowTheParentMap() {
        data class Case(
            val name: String,
            val root: Int,
            val parentByPid: Map<Int, Int>,
            val expected: Set<Int>,
        )
        listOf(
            Case("empty", 10, emptyMap(), emptySet()),
            Case("root only", 10, mapOf(10 to 1), emptySet()),
            Case("direct children", 10, mapOf(11 to 10, 12 to 10, 10 to 1), setOf(11, 12)),
            Case(
                "nested grandchildren",
                10,
                mapOf(11 to 10, 12 to 11, 13 to 11, 14 to 1, 10 to 1),
                setOf(11, 12, 13),
            ),
            Case("unrelated siblings excluded", 10, mapOf(11 to 10, 20 to 2, 10 to 1), setOf(11)),
            Case("invalid root", 0, mapOf(11 to 10), emptySet()),
        ).forEach { case ->
            assertEquals(case.name, case.expected, UnixProcessTree.descendantPids(case.root, case.parentByPid))
        }
    }

    @Test
    fun parseStatusPpidReadsTheKernelField() {
        data class Case(val name: String, val status: String, val expected: Int?)
        listOf(
            Case("normal", "Name:\tsh\nPPid:\t42\nUid:\t0", 42),
            Case("spaces", "PPid:   7\n", 7),
            Case("missing", "Name:\tsh\n", null),
            Case("junk", "PPid:\tnope\n", null),
        ).forEach { case ->
            if (case.expected == null) {
                assertNull(case.name, UnixProcessTree.parseStatusPpid(case.status))
            } else {
                assertEquals(case.name, case.expected, UnixProcessTree.parseStatusPpid(case.status))
            }
        }
    }
}
