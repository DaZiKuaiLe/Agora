package com.newoether.agora.tool

import org.junit.Assert.assertEquals
import org.junit.Test

class ProcessToolsTest {

    @Test
    fun waitMsAndCountdown_table() {
        data class Case(
            val name: String,
            val optionalRaw: String,
            val optionalMs: Int?,
            val requiredRaw: String,
            val requiredMs: Int?,
            val waitMs: Int,
            val elapsedMs: Long,
            val remaining: Int?,
            val formatted: String?,
        )
        val cases = listOf(
            Case("omit start wait", "", 0, "", null, 0, 0, null, null),
            Case("subsecond hidden", "500", 500, "500", 500, 500, 0, null, null),
            Case("five seconds start", "5000", 5000, "5000", 5000, 5000, 0, 5, "5"),
            Case("one second left", "5000", 5000, "5000", 5000, 5000, 4_000, 1, "1"),
            Case("under one second gone", "5000", 5000, "5000", 5000, 5000, 4_200, null, null),
            Case("minute format", "125000", 125000, "125000", 125000, 125000, 0, 125, "2:05"),
            Case("garbage", "nope", null, "nope", null, 1000, 0, 1, "1"),
        )
        cases.forEach { case ->
            assertEquals(case.name, case.optionalMs, ProcessTools.parseOptionalWaitMs(case.optionalRaw))
            assertEquals(case.name, case.requiredMs, ProcessTools.parseRequiredWaitMs(case.requiredRaw))
            val remaining = ProcessTools.remainingCountdownSeconds(case.waitMs, 1_000L, 1_000L + case.elapsedMs)
            assertEquals(case.name, case.remaining, remaining)
            assertEquals(
                case.name,
                case.formatted,
                remaining?.let { ProcessTools.formatCountdown(it) },
            )
        }
    }
}
