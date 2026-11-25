package com.newoether.agora.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AsyncCommandIdTest {

    @Test
    fun parse_roundTripAndRejects() {
        data class Case(val name: String, val raw: String, val expected: AsyncCommandId?)
        val local = AsyncCommandId.local("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val ssh = AsyncCommandId.ssh("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", "cccccccc-cccc-cccc-cccc-cccccccccccc")
        val cases = listOf(
            Case("local", local.encode(), local),
            Case("ssh", ssh.encode(), ssh),
            Case("blank", "", null),
            Case("job leftover", "job-123", null),
            Case("ssh missing run", "ssh:device-only", null),
            Case("local empty", "local:", null),
        )
        cases.forEach { case ->
            assertEquals(case.name, case.expected, AsyncCommandId.parse(case.raw))
        }
    }
}
