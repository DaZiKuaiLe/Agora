package com.newoether.agora.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactBatchPlannerTest {
    private fun t(id: String, locked: Boolean = false, batched: Boolean = false) =
        CompactBatchPlanner.TurnRef(id, locked, batched)

    @Test
    fun precompressOnlyFullBatchesAndSplitsOnLock() {
        var n = 0
        val turns = listOf(
            t("1"), t("2"), t("3"), t("4"), t("5"),
            t("L", locked = true),
            t("6"), t("7"), t("8"), t("9"), t("10"), t("11"), t("12"), t("13"),
        )
        val plans = CompactBatchPlanner.planBatches(
            turns = turns,
            batchSize = 8,
            allowPartial = false,
            newBatchId = { "b${++n}" },
        )
        assertEquals(1, plans.size)
        assertEquals((6..13).map { it.toString() }, plans.single().turnIds)
        assertEquals(false, plans.single().partial)
    }

    @Test
    fun formalCompressAllowsPartialBeforeLock() {
        var n = 0
        val turns = listOf(
            t("1"), t("2"), t("3"), t("4"), t("5"),
            t("L", locked = true),
            t("6"), t("7"),
        )
        val plans = CompactBatchPlanner.planBatches(
            turns = turns,
            batchSize = 8,
            allowPartial = true,
            newBatchId = { "b${++n}" },
        )
        assertEquals(2, plans.size)
        assertEquals(listOf("1", "2", "3", "4", "5"), plans[0].turnIds)
        assertTrue(plans[0].partial)
        assertEquals(listOf("6", "7"), plans[1].turnIds)
        assertTrue(plans[1].partial)
    }

    @Test
    fun compressiblePrefixDropsRetainWindow() {
        val turns = (1..10).map { t(it.toString()) }
        val prefix = CompactBatchPlanner.compressiblePrefix(turns, retainCount = 8)
        assertEquals((1..2).map { it.toString() }, prefix.map { it.turnId })
    }
}
