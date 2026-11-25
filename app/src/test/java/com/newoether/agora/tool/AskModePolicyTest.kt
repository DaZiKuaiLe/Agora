package com.newoether.agora.tool

import org.junit.Assert.assertEquals
import org.junit.Test

class AskModePolicyTest {
    @Test
    fun allows_table() {
        data class Case(val name: String, val tool: String, val allowed: Boolean)
        listOf(
            Case("read local", "read_local_file", true),
            Case("grep remote", "grep_remote", true),
            Case("web fetch", "web_fetch", true),
            Case("detail store", DetailArchive.QUERY_TOOL, true),
            Case("write local", "write_local_file", false),
            Case("str replace", "str_replace_remote", false),
            Case("download writes disk", "download_file", false),
            Case("open file", "open_file", false),
            Case("local command", "execute_local_command", true),
            Case("remote command", "execute_remote_command", true),
            Case("start process", ProcessTools.START_LOCAL, true),
            Case("wait process", ProcessTools.WAIT, true),
            Case("list processes", ProcessTools.LIST, true),
            Case("mcp", "mcp_server_write_abc123", false),
            Case("blank", "", false),
        ).forEach { case ->
            assertEquals(case.name, case.allowed, AskModePolicy.allows(case.tool))
        }
    }
}
