package com.newoether.agora.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResidencyChaptersTest {
    @Test
    fun parseEncodeAndWire_roundTripChapters() {
        data class Case(
            val name: String,
            val stored: String,
            val chapters: List<String>,
            val freezeCount: Int,
            val wireHasBreak: Boolean,
        )
        val cases = listOf(
            Case("empty", "", emptyList(), 0, false),
            Case("legacy single blob", "Prior facts", listOf("Prior facts"), 0, false),
            Case(
                "two chapters",
                "Old$RESIDENCY_CHAPTER_STORAGE_MARK New",
                listOf("Old", "New"),
                2,
                true,
            ),
        )
        for (case in cases) {
            val parsed = parseResidencyChapters(case.stored)
            assertEquals(case.name, case.chapters, parsed)
            if (parsed.isNotEmpty()) {
                assertEquals(case.name, parsed, parseResidencyChapters(encodeResidencyChapters(parsed)))
            }
            assertEquals(case.name, case.freezeCount, freezeChapterCount(parsed))
            val wire = formatResidencyChaptersForWire(parsed)
            assertEquals(case.name, case.wireHasBreak, RESIDENCY_CHAPTER_WIRE_BREAK.trim() in wire)
            if (parsed.size <= 1) {
                assertEquals(case.name, parsed.singleOrNull().orEmpty(), wire)
            } else {
                assertTrue(case.name, wire.contains("[整合段 1/${parsed.size}]"))
                parsed.forEach { chapter -> assertTrue(case.name, wire.contains(chapter)) }
            }
            assertEquals(case.name, case.freezeCount, freezeChapterCountFromWire(wire))
        }
    }

    @Test
    fun freezeChapterCountFromWire_readsLegacyFreezeHeaders() {
        val legacy = "[冻结章 1/2]\nOld$RESIDENCY_CHAPTER_WIRE_BREAK[冻结章 2/2]\nNew"
        assertEquals(2, freezeChapterCountFromWire(legacy))
        val current = formatResidencyChaptersForWire(listOf("Old", "New"))
        assertEquals(2, freezeChapterCountFromWire(current))
    }
}
