package com.newoether.agora.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class SystemNoticeTest {
    @Test
    fun formatWireTimestamp_usesZoneOffsetAtThatInstant() {
        val shanghai = ZoneId.of("Asia/Shanghai")
        val epoch = ZonedDateTime.of(2026, 10, 3, 3, 21, 0, 0, shanghai).toInstant().toEpochMilli()
        data class Case(val name: String, val zone: String, val expected: String)
        listOf(
            Case("shanghai", "Asia/Shanghai", "2026-10-03 03:21:00 Asia/Shanghai (UTC+08:00)"),
            Case("utc", "UTC", "2026-10-02 19:21:00 UTC (UTC+00:00)"),
            Case("new york", "America/New_York", "2026-10-02 15:21:00 America/New_York (UTC-04:00)"),
        ).forEach { case ->
            assertEquals(case.name, case.expected, formatWireTimestamp(epoch, ZoneId.of(case.zone)))
        }
    }
}
