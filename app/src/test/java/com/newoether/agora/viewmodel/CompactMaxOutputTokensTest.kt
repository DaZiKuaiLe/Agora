package com.newoether.agora.viewmodel

import com.newoether.agora.model.ContextBudget
import org.junit.Assert.assertEquals
import org.junit.Test

class CompactMaxOutputTokensTest {
    @Test
    fun compactMaxOutputTokens_matchesFreezeThreshold() {
        data class Case(val name: String, val freezeThreshold: Int, val expected: Int)
        val cases = listOf(
            Case("default freeze threshold", ContextBudget.DEFAULT_FREEZE_THRESHOLD, 262_144),
            Case("user 64K", 65_536, 65_536),
            Case("user 1M", 1_048_576, 1_048_576),
            Case("zero falls back to default freeze", 0, ContextBudget.DEFAULT_FREEZE_THRESHOLD),
            Case("negative falls back to default freeze", -1, ContextBudget.DEFAULT_FREEZE_THRESHOLD),
        )
        for (case in cases) {
            assertEquals(case.name, case.expected, compactMaxOutputTokens(case.freezeThreshold))
        }
    }
}
