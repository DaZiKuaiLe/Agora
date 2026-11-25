package com.newoether.agora.tool

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.viewmodel.GenerationContext

internal object FileToolDefinitions {
    val REMOTE_NAMES = setOf(
        "read_remote_file",
        "grep_remote",
        "glob_remote_files",
        "list_remote_files",
        "str_replace_remote",
        "write_remote_file",
    )

    val NAMES = setOf(
        "read_local_file",
        "grep_local",
        "glob_local_files",
        "list_local_files",
        "str_replace_local",
        "write_local_file",
    ) + REMOTE_NAMES

    fun build(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.shellEnabled) return emptyList()
        val remoteNames = ctx.shellDevices
            .filter { it.type == "ssh" }
            .map { it.name.ifBlank { "Untitled" } }
        val serverProp = when {
            remoteNames.isEmpty() ->
                ToolProperty("string", "The SSH remote terminal name.")
            remoteNames.size == 1 ->
                ToolProperty("string", "The SSH remote terminal name. Omit to use the only available one: ${remoteNames[0]}.")
            else ->
                ToolProperty("string", "The SSH remote terminal name. Available: ${remoteNames.joinToString(", ")}.")
        }
        val remoteRequired = if (remoteNames.size <= 1) emptyList() else listOf("server")
        val tools = mutableListOf<ToolDefinition>()
        if (ctx.localCommandEnabled) {
            tools += localTools()
        }
        if (remoteNames.isNotEmpty()) {
            tools += remoteTools(serverProp, remoteRequired)
        }
        return tools
    }

    private val ignoreProp = ToolProperty(
        "array",
        "Extra directory names to skip (optional). Merged with the default noise list.",
        items = ToolProperty("string", "Directory name."),
    )

    private fun localTools(): List<ToolDefinition> = listOf(
        tool(
            "read_local_file",
            "Read a UTF-8 text file on this device. Optional start_line/end_line (1-based, inclusive). " +
                FileTextOps.READ_LIMITS +
                " Do not use execute_local_command or cat/sed/rg to read files.",
            mapOf(
                "path" to ToolProperty("string", "Absolute or app-relative local path."),
                "start_line" to ToolProperty(
                    "integer",
                    "First line to return (1-based, optional). Required for files over ${FileTextOps.DEFAULT_MAX_READ_BYTES} bytes.",
                ),
                "end_line" to ToolProperty(
                    "integer",
                    "Last line to return (inclusive, optional). The returned slice is capped at ${FileTextOps.DEFAULT_MAX_READ_BYTES} bytes.",
                ),
            ),
            listOf("path"),
        ),
        tool(
            "list_local_files",
            "List one directory on this device (files and subfolder names; not recursive). " +
                FileTextOps.LIST_LIMITS +
                " Do not use execute_local_command or ls for this. For recursive name search use glob_local_files.",
            mapOf(
                "path" to ToolProperty("string", "Directory to list (optional)."),
                "dirs_only" to ToolProperty("boolean", "Only return subfolders (optional, default false)."),
                "offset" to ToolProperty(
                    "integer",
                    "Skip this many earlier entries to fetch the next page (optional, default 0).",
                ),
            ),
            emptyList(),
        ),
        tool(
            "glob_local_files",
            "Find file names on this device matching a glob (recursive; does not open contents). " +
                FileTextOps.GLOB_LIMITS +
                " Do not use execute_local_command or find for this.",
            mapOf(
                "pattern" to ToolProperty("string", "Filename glob (required)."),
                "path" to ToolProperty("string", "Directory to search from (optional)."),
                "no_ignore" to ToolProperty(
                    "boolean",
                    "Also search noise directories such as node_modules and .git (optional, default false).",
                ),
                "ignore" to ignoreProp,
                "offset" to ToolProperty(
                    "integer",
                    "Skip this many earlier paths to fetch the next page (optional, default 0).",
                ),
            ),
            listOf("pattern"),
        ),
        tool(
            "grep_local",
            "Search file contents on this device with a regex (recursive; not file names). " +
                FileTextOps.GREP_LIMITS +
                " Do not use execute_local_command or rg as a file-search API.",
            mapOf(
                "pattern" to ToolProperty("string", "Regex search pattern (Java regex locally; spaces are literal)."),
                "path" to ToolProperty("string", "Directory or file to search (optional; default app files)."),
                "glob" to ToolProperty("string", "Filename glob filter (optional; * is one segment, ** crosses directories)."),
                "no_ignore" to ToolProperty(
                    "boolean",
                    "Also search noise directories such as node_modules and .git (optional, default false).",
                ),
                "ignore" to ignoreProp,
                "offset" to ToolProperty(
                    "integer",
                    "Skip this many earlier matches to fetch the next page (optional, default 0).",
                ),
            ),
            listOf("pattern"),
        ),
        tool(
            "str_replace_local",
            "Replace one exact old_string hunk in a local UTF-8 file. old_string must occur once unless replace_all is true. " +
                "CRLF and LF are treated as the same line break; old_string is literal text, not a regex. " +
                "0 or many matches fail with line hints; nothing is written. Prefer this for one-hunk edits; use write_local_file for a whole file. " +
                FileTextOps.REPLACE_LIMITS,
            mapOf(
                "path" to ToolProperty("string", "Local file path."),
                "old_string" to ToolProperty("string", "Exact text that must occur once (unless replace_all)."),
                "new_string" to ToolProperty("string", "Replacement text."),
                "replace_all" to ToolProperty("boolean", "Replace every occurrence (optional, default false)."),
            ),
            listOf("path", "old_string", "new_string"),
        ),
        tool(
            "write_local_file",
            "Write a UTF-8 file on this device (creates parents, atomic tmp+rename). Overwrites the whole file. " +
                "Shared-storage writes may require confirmation and all-files access. " +
                FileTextOps.WRITE_LIMITS,
            mapOf(
                "path" to ToolProperty("string", "Local file path."),
                "content" to ToolProperty(
                    "string",
                    "Full UTF-8 file contents (max ${FileTextOps.MAX_WRITE_BYTES} bytes).",
                ),
            ),
            listOf("path", "content"),
        ),
    )

    private fun remoteTools(
        serverProp: ToolProperty,
        remoteRequired: List<String>,
    ): List<ToolDefinition> = listOf(
        tool(
            "read_remote_file",
            "Read a UTF-8 text file on an SSH host over SFTP (not via cat). Optional start_line/end_line (1-based, inclusive). " +
                FileTextOps.READ_LIMITS +
                " Size comes from SFTP stat; over ${FileTextOps.DEFAULT_MAX_READ_BYTES} bytes without a range does not download the file.",
            mapOf(
                "path" to ToolProperty("string", "Remote path. Windows may use C:\\\\Users\\\\... or C:/Users/..."),
                "server" to serverProp,
                "start_line" to ToolProperty(
                    "integer",
                    "First line to return (1-based, optional). Required for files over ${FileTextOps.DEFAULT_MAX_READ_BYTES} bytes.",
                ),
                "end_line" to ToolProperty(
                    "integer",
                    "Last line to return (inclusive, optional). The returned slice is capped at ${FileTextOps.DEFAULT_MAX_READ_BYTES} bytes.",
                ),
            ),
            listOf("path") + remoteRequired,
        ),
        tool(
            "list_remote_files",
            "List one directory on an SSH host (files and subfolder names; not recursive). Uses one SFTP ls. " +
                FileTextOps.LIST_LIMITS +
                " Do not use execute_remote_command or ls/dir for this. For recursive name search use glob_remote_files.",
            mapOf(
                "path" to ToolProperty("string", "Remote directory (optional)."),
                "dirs_only" to ToolProperty("boolean", "Only return subfolders (optional, default false)."),
                "offset" to ToolProperty(
                    "integer",
                    "Skip this many earlier entries to fetch the next page (optional, default 0).",
                ),
                "server" to serverProp,
            ),
            remoteRequired,
        ),
        tool(
            "glob_remote_files",
            "Find file names on an SSH host matching a glob (recursive). Runs find or PowerShell on the host; does not download files. " +
                FileTextOps.GLOB_LIMITS +
                " If the host cannot search, this tool fails — do not expect an SFTP fallback.",
            mapOf(
                "pattern" to ToolProperty("string", "Filename glob (required)."),
                "path" to ToolProperty("string", "Remote directory to search from (optional)."),
                "no_ignore" to ToolProperty(
                    "boolean",
                    "Also search noise directories such as node_modules and .git (optional, default false).",
                ),
                "ignore" to ignoreProp,
                "offset" to ToolProperty(
                    "integer",
                    "Skip this many earlier paths to fetch the next page (optional, default 0).",
                ),
                "server" to serverProp,
            ),
            listOf("pattern") + remoteRequired,
        ),
        tool(
            "grep_remote",
            "Search file contents on an SSH host with a regex (recursive; not file names). Runs grep or PowerShell Select-String on the host. " +
                FileTextOps.GREP_LIMITS +
                " If the host cannot search, this tool fails with a reason — it does not download files to grep locally.",
            mapOf(
                "pattern" to ToolProperty("string", "Regex search pattern (host engine: grep or PowerShell)."),
                "path" to ToolProperty("string", "Remote directory or file (optional)."),
                "glob" to ToolProperty("string", "Filename glob filter (optional)."),
                "no_ignore" to ToolProperty(
                    "boolean",
                    "Also search noise directories such as node_modules and .git (optional, default false).",
                ),
                "ignore" to ignoreProp,
                "offset" to ToolProperty(
                    "integer",
                    "Skip this many earlier matches to fetch the next page (optional, default 0).",
                ),
                "server" to serverProp,
            ),
            listOf("pattern") + remoteRequired,
        ),
        tool(
            "str_replace_remote",
            "Replace one exact old_string hunk in a remote UTF-8 file over SFTP. old_string must occur once unless replace_all. " +
                "CRLF and LF are treated as the same line break; old_string is literal text, not a regex. " +
                "0 or many matches fail with line hints. Prefer this for one-hunk edits; use write_remote_file for a whole file. " +
                FileTextOps.REPLACE_LIMITS,
            mapOf(
                "path" to ToolProperty("string", "Remote file path."),
                "old_string" to ToolProperty("string", "Exact text that must occur once (unless replace_all)."),
                "new_string" to ToolProperty("string", "Replacement text."),
                "replace_all" to ToolProperty("boolean", "Replace every occurrence (optional, default false)."),
                "server" to serverProp,
            ),
            listOf("path", "old_string", "new_string") + remoteRequired,
        ),
        tool(
            "write_remote_file",
            "Write a UTF-8 file on an SSH host over SFTP (atomic tmp+rename). Overwrites the whole file. May require confirmation. " +
                FileTextOps.WRITE_LIMITS,
            mapOf(
                "path" to ToolProperty("string", "Remote file path."),
                "content" to ToolProperty(
                    "string",
                    "Full UTF-8 file contents (max ${FileTextOps.MAX_WRITE_BYTES} bytes).",
                ),
                "server" to serverProp,
            ),
            listOf("path", "content") + remoteRequired,
        ),
    )

    private fun tool(
        name: String,
        description: String,
        properties: Map<String, ToolProperty>,
        required: List<String>,
    ) = ToolDefinition(
        function = ToolFunction(
            name = name,
            description = description,
            parameters = ToolParameters(properties = properties, required = required),
        ),
    )
}
