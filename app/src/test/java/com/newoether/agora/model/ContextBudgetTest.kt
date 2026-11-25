package com.newoether.agora.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ContextBudgetTest {
    @Test
    fun legacyMessageWindowsMigrateToUsefulTokenBudgets() {
        assertEquals(20_480, ContextBudget.normalize(20))
        assertEquals(102_400, ContextBudget.normalize(100))
    }

    @Test
    fun tokenBudgetsAreClampedAndNullUsesNewDefault() {
        assertEquals(ContextBudget.DEFAULT_TOKENS, ContextBudget.normalize(null))
        assertEquals(ContextBudget.MIN_TOKENS, ContextBudget.normalize(1_000))
        assertEquals(ContextBudget.MAX_TOKENS, ContextBudget.normalize(Int.MAX_VALUE))
    }

    @Test
    fun compactLabelsDoNotMigrateLiveLowTokenUsageAsLegacySettings() {
        assertEquals("0", ContextBudget.compactLabel(0))
        assertEquals("64", ContextBudget.compactLabel(64))
        assertEquals("1000", ContextBudget.compactLabel(1_000))
        assertEquals("1K", ContextBudget.compactLabel(1_001))
        assertEquals("1K", ContextBudget.compactLabel(1_023))
        assertEquals("1K", ContextBudget.compactLabel(1_024))
        assertEquals("1.5K", ContextBudget.compactLabel(1_536))
        assertEquals("4K", ContextBudget.compactLabel(4_096))
        assertEquals("1M", ContextBudget.compactLabel(1_048_576))
    }

    @Test
    fun freezeThresholdDefaultIsThe256kPreset() {
        assertEquals(262_144, ContextBudget.DEFAULT_FREEZE_THRESHOLD)
        assertEquals(
            ContextBudget.DEFAULT_FREEZE_THRESHOLD,
            ContextBudget.nearestPreset(ContextBudget.DEFAULT_FREEZE_THRESHOLD),
        )
        data class Case(val name: String, val input: Int, val expected: Int)
        val cases = listOf(
            Case("below floor snaps to 4K", 1, 4_096),
            Case("between 128K and 256K snaps nearer", 200_000, 262_144),
            Case("above ceiling snaps to 1M", Int.MAX_VALUE, 1_048_576),
        )
        for (case in cases) {
            assertEquals(case.name, case.expected, ContextBudget.nearestPreset(case.input))
        }
    }

    @Test
    fun groupedLabelsUseCommaThousandsSeparators() {
        assertEquals("0", ContextBudget.groupedLabel(0))
        assertEquals("64", ContextBudget.groupedLabel(64))
        assertEquals("999", ContextBudget.groupedLabel(999))
        assertEquals("1,000", ContextBudget.groupedLabel(1_000))
        assertEquals("1,024", ContextBudget.groupedLabel(1_024))
        assertEquals("32,768", ContextBudget.groupedLabel(32_768))
        assertEquals("568,442", ContextBudget.groupedLabel(568_442))
        assertEquals("1,048,576", ContextBudget.groupedLabel(1_048_576))
    }
}
