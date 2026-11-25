package com.newoether.agora.tool

import com.newoether.agora.viewmodel.GenerationContext
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test

class WebSearchToolProviderTest {
    private val provider = WebSearchToolProvider()
    private val enabledCtx = GenerationContext(webSearchEnabled = true)
    private val disabledCtx = GenerationContext(webSearchEnabled = false)

    @Test
    fun definitions_whenEnabled_returnsTwoTools() {
        val defs = provider.definitions(enabledCtx)
        assertEquals(2, defs.size)
        assertEquals("web_search", defs[0].function.name)
        assertEquals("web_fetch", defs[1].function.name)
    }

    @Test
    fun definitions_exposesOnlyCurrentProviderParams() {
        val zhipuDefs = provider.definitions(GenerationContext(webSearchEnabled = true, webSearchProvider = "zhipu"))
        val zhipuKeys = zhipuDefs[0].function.parameters.properties.keys
        assertEquals(setOf("query", "num_results", "search_engine", "search_recency_filter", "search_domain_filter", "content_size"), zhipuKeys)

        val baiduDefs = provider.definitions(GenerationContext(webSearchEnabled = true, webSearchProvider = "baidu"))
        val baiduKeys = baiduDefs[0].function.parameters.properties.keys
        assertEquals(setOf("query", "num_results", "recency_filter", "include_video", "include_image", "site_filter"), baiduKeys)
    }

    @Test
    fun definitions_toolDescriptionIncludesProviderConvention() {
        val defs = provider.definitions(GenerationContext(webSearchEnabled = true, webSearchProvider = "zhipu"))
        val description = defs[0].function.description
        assertTrue("zhipu convention must be appended to tool description", "search_domain_filter" in description || "search_engine" in description)
    }

    @Test
    fun definitions_whenDisabled_returnsEmpty() {
        assertTrue(provider.definitions(disabledCtx).isEmpty())
    }

    @Test
    fun handles_returnsTrueForWebTools() {
        assertTrue(provider.handles("web_search"))
        assertTrue(provider.handles("web_fetch"))
        assertFalse(provider.handles("unknown"))
    }
}

class RagToolProviderTest {
    private val provider = RagToolProvider(mockk(relaxed = true))
    private val enabledCtx = GenerationContext(accessPastConversations = true)
    private val disabledCtx = GenerationContext(accessPastConversations = false)

    @Test
    fun definitions_whenEnabled_returnsThreeTools() {
        val defs = provider.definitions(enabledCtx)
        assertEquals(3, defs.size)
        assertEquals("search_conversations", defs[0].function.name)
        assertEquals("list_conversations", defs[1].function.name)
        assertEquals("read_conversation", defs[2].function.name)
    }

    @Test
    fun definitions_whenPastAccessDisabled_returnsEmptyWithoutConversation() {
        assertTrue(provider.definitions(disabledCtx).isEmpty())
    }

    @Test
    fun definitions_offersCurrentConversationSearchWhenConversationIdSet() {
        val defs = provider.definitions(
            GenerationContext(accessPastConversations = false, conversationId = "c1"),
        )
        assertEquals(listOf("search_current_conversation"), defs.map { it.function.name })
    }

    @Test
    fun handles_returnsTrueForConversationTools() {
        // RagToolProvider now owns execution of the conversation tools.
        assertTrue(provider.handles("search_current_conversation"))
        assertTrue(provider.handles("search_conversations"))
        assertTrue(provider.handles("list_conversations"))
        assertTrue(provider.handles("read_conversation"))
        assertFalse(provider.handles("web_search"))
    }
}

class ShellToolProviderTest {
    private val provider = ShellToolProvider()
    private val emptyCtx = GenerationContext(shellEnabled = false)
    private val disabledCtx = GenerationContext(shellEnabled = true, shellDevices = emptyList())

    private fun sshDevice(name: String, host: String = "example.com") =
        com.newoether.agora.data.ShellDeviceConfig(
            name = name,
            type = "ssh",
            sshHost = host,
        )

    @Test
    fun definitions_whenDisabled_returnsEmpty() {
        assertTrue(provider.definitions(emptyCtx).isEmpty())
        assertTrue(provider.definitions(disabledCtx).isEmpty())
    }

    @Test
    fun definitions_whenSingleDevice_shellAndTransferTools() {
        val ctx = GenerationContext(
            shellEnabled = true,
            shellDevices = listOf(sshDevice("server1")),
        )
        val defs = provider.definitions(ctx)
        val names = defs.map { it.function.name }.toSet()
        assertEquals(
            setOf(
                "list_remote_shells",
                "execute_remote_command",
                "start_remote_process",
                "get_process",
                "list_background_processes",
                "wait_process",
                "stop_process",
                "upload_file_to_server",
                "download_file",
                "read_image",
                "open_file",
                "read_remote_file",
                "grep_remote",
                "glob_remote_files",
                "list_remote_files",
                "str_replace_remote",
                "write_remote_file",
            ),
            names,
        )
        val command = defs.single { it.function.name == "execute_remote_command" }
        assertFalse(command.function.parameters.properties.containsKey("background"))
        val listDesc = defs.single { it.function.name == "list_remote_shells" }.function.description
        assertTrue(listDesc.contains("command_syntax"))
        val byName = defs.associate { it.function.name to it.function.description }
        assertTrue(byName.getValue("read_remote_file").contains(FileTextOps.DEFAULT_MAX_READ_BYTES.toString()))
        assertTrue(byName.getValue("grep_remote").contains(FileTextOps.MAX_GREP_MATCHES.toString()))
        assertTrue(byName.getValue("glob_remote_files").contains(FileTextOps.MAX_LIST_FILES.toString()))
        assertTrue(byName.getValue("list_remote_files").contains(FileTextOps.MAX_LIST_FILES.toString()))
        assertTrue(byName.getValue("str_replace_remote").contains(FileTextOps.MAX_REPLACE_BYTES.toString()))
        assertTrue(byName.getValue("write_remote_file").contains(FileTextOps.MAX_WRITE_BYTES.toString()))
    }

    @Test
    fun listRemoteShells_includesShellFingerprint() = kotlinx.coroutines.test.runTest {
        val ctx = GenerationContext(
            shellEnabled = true,
            shellDevices = listOf(
                com.newoether.agora.data.ShellDeviceConfig(
                    name = "win",
                    type = "ssh",
                    sshHost = "10.0.0.1",
                    sshShell = "auto",
                    sshDetectedShell = "cmd",
                ),
            ),
        )
        val raw = provider.execute("list_remote_shells", "{}", ctx)
        assertTrue(raw.contains("\"command_syntax\":\"powershell\""))
        assertTrue(raw.contains("\"shell\":\"cmd\""))
        assertTrue(raw.contains("\"shell_preference\":\"auto\""))
        assertTrue(raw.contains("\"os_hint\":\"windows\""))
    }

    @Test
    fun definitions_whenLocalOnly_includesLocalAndReadImage() {
        val ctx = GenerationContext(
            shellEnabled = true,
            localCommandEnabled = true,
            shellDevices = emptyList(),
        )
        val names = provider.definitions(ctx).map { it.function.name }.toSet()
        assertEquals(
            setOf(
                "execute_local_command",
                "start_local_process",
                "get_process",
                "list_background_processes",
                "wait_process",
                "stop_process",
                "request_all_files_access",
                "read_image",
                "open_file",
                "read_local_file",
                "grep_local",
                "glob_local_files",
                "list_local_files",
                "str_replace_local",
                "write_local_file",
            ),
            names,
        )
    }

    @Test
    fun definitions_whenMultiDevice_commandRequiresServer() {
        val ctx = GenerationContext(
            shellEnabled = true,
            shellDevices = listOf(sshDevice("s1"), sshDevice("s2", host = "other.example")),
        )
        val defs = provider.definitions(ctx)
        val cmdTool = defs.find { it.function.name == "execute_remote_command" }
        assertNotNull(cmdTool)
        assertEquals(
            listOf("command", "server", "timeout_ms"),
            cmdTool!!.function.parameters.required,
        )
    }

    @Test
    fun definitions_singleDevice_commandRequiresTimeout() {
        val ctx = GenerationContext(
            shellEnabled = true,
            shellDevices = listOf(sshDevice("s1")),
        )
        val defs = provider.definitions(ctx)
        val cmdTool = defs.single { it.function.name == "execute_remote_command" }
        assertEquals(listOf("command", "timeout_ms"), cmdTool.function.parameters.required)
        val startTool = defs.single { it.function.name == "start_remote_process" }
        assertEquals(listOf("command"), startTool.function.parameters.required)
        assertTrue(
            cmdTool.function.parameters.properties.getValue("timeout_ms").description
                .contains("295000"),
        )
    }

    @Test
    fun definitions_ignoresLegacyConchDevices() {
        val ctx = GenerationContext(
            shellEnabled = true,
            shellDevices = listOf(
                com.newoether.agora.data.ShellDeviceConfig(
                    name = "legacy",
                    type = "conch",
                    serverUrl = "http://localhost",
                ),
            ),
        )
        assertTrue(provider.definitions(ctx).isEmpty())
    }

    @Test
    fun executeShellCommand_missingTimeout_returnsError() = kotlinx.coroutines.test.runTest {
        val ctx = GenerationContext(
            shellEnabled = true,
            shellDevices = listOf(sshDevice("s1")),
        )
        val result = provider.execute(
            "execute_remote_command", """{"command":"echo hi","server":"s1"}""", ctx,
        )
        assertTrue(result.contains("timeout_ms is required"))
    }

    @Test
    fun execute_openFile_rejectsBlankAndDisallowedSchemes() = kotlinx.coroutines.test.runTest {
        val ctx = GenerationContext(
            shellEnabled = true,
            localCommandEnabled = true,
            shellDevices = emptyList(),
        )
        data class Case(val name: String, val args: String, val needle: String)
        listOf(
            Case("blank", """{"path":""}""", "path is required"),
            Case("missing", "{}", "path is required"),
            Case("file scheme", """{"path":"file:///sdcard/a.pdf"}""", "http/https"),
            Case("javascript", """{"path":"javascript:alert(1)"}""", "http/https"),
        ).forEach { case ->
            val result = provider.execute("open_file", case.args, ctx)
            assertTrue(case.name, result.contains(case.needle))
            assertTrue(case.name, result.contains("\"error\""))
        }
    }

    @Test
    fun handles_returnsTrueForShellAndTransferTools() {
        assertTrue(provider.handles("list_remote_shells"))
        assertTrue(provider.handles("execute_remote_command"))
        assertTrue(provider.handles("execute_local_command"))
        assertTrue(provider.handles("request_all_files_access"))
        assertTrue(provider.handles("read_image"))
        assertTrue(provider.handles("open_file"))
        assertTrue(provider.handles("upload_file_to_server"))
        assertTrue(provider.handles("download_file"))
        assertTrue(provider.handles("read_local_file"))
        assertTrue(provider.handles("read_remote_file"))
        assertTrue(provider.handles("start_local_process"))
        assertTrue(provider.handles("get_process"))
        assertTrue(provider.handles("list_background_processes"))
        assertFalse(provider.handles("file_read"))
        assertFalse(provider.handles("view_image"))
        assertFalse(provider.handles("list_remote_jobs"))
        assertFalse(provider.handles("unknown"))
    }
}

