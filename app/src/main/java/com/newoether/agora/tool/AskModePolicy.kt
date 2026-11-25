package com.newoether.agora.tool

/**
 * Ask mode keeps search, read, and command tools so the model can inspect.
 * File writes, uploads, and unknown-side-effect tools are omitted from the request
 * and cannot be authorized. Commands stay available; the prompt forbids using them to mutate.
 */
internal object AskModePolicy {
    val ALLOWED = setOf(
        "web_search",
        "web_fetch",
        "search_current_conversation",
        "search_conversations",
        "list_conversations",
        "read_conversation",
        DetailArchive.QUERY_TOOL,
        "list_skill_files",
        "read_skill_file",
        "list_remote_shells",
        "read_image",
        "read_local_file",
        "grep_local",
        "glob_local_files",
        "list_local_files",
        "read_remote_file",
        "grep_remote",
        "glob_remote_files",
        "list_remote_files",
        "execute_local_command",
        "execute_remote_command",
        ProcessTools.START_LOCAL,
        ProcessTools.START_REMOTE,
        ProcessTools.GET,
        ProcessTools.LIST,
        ProcessTools.WAIT,
        ProcessTools.STOP,
    )

    fun allows(name: String): Boolean =
        name.isNotBlank() && !name.startsWith("mcp_") && name in ALLOWED
}
