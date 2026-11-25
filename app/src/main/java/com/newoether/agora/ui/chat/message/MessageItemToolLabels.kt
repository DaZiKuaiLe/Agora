package com.newoether.agora.ui.chat.message

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.newoether.agora.R
import com.newoether.agora.model.MessageSegment

/**
 * The only localization layer for tool cards. Parsing and lifecycle inference live in
 * [ToolPresentationResolver]; compact, timeline and detail surfaces all call these functions.
 */
@Composable
internal fun toolDisplayName(segment: MessageSegment): String {
    val toolName = segment.toolName.orEmpty()
    val kind = ToolPresentationResolver.kindForToolName(toolName)
    if (kind == ToolKind.MCP) {
        return mcpToolDisplayName(
            publicName = toolName,
            resolvedName = segment.toolDisplayName,
        ) ?: "MCP"
    }
    return toolBaseDisplayName(
        kind = kind,
        toolName = toolName,
    )
}

private val MCP_PUBLIC_TOOL_NAME =
    Regex("""^mcp_[A-Za-z0-9]+_(.+)_[0-9a-fA-F]{6}$""")
private val TOOL_NAME_SEPARATOR = Regex("""[\s._-]+""")

/**
 * New calls use [resolvedName]. The public-name decoder is intentionally fallback-only for
 * history written before provider presentation metadata was persisted.
 */
internal fun mcpToolDisplayName(
    publicName: String?,
    resolvedName: String?,
): String? {
    val remoteName = resolvedName
        ?.takeIf { it.isNotBlank() }
        ?: publicName
            ?.let(MCP_PUBLIC_TOOL_NAME::matchEntire)
            ?.groupValues
            ?.getOrNull(1)
    return remoteName
        ?.trim()
        ?.split(TOOL_NAME_SEPARATOR)
        ?.filter(String::isNotBlank)
        ?.joinToString(" ") { word ->
            word.replaceFirstChar { char ->
                if (char.isLowerCase()) char.uppercaseChar() else char
            }
        }
        ?.takeIf(String::isNotBlank)
}

@Composable
private fun toolBaseDisplayName(
    kind: ToolKind,
    toolName: String,
): String = when (kind) {
    ToolKind.MEMORY_LIST -> stringResource(R.string.tool_look_up_memories)
    ToolKind.MEMORY_READ -> stringResource(R.string.tool_read_memory)
    ToolKind.MEMORY_CREATE -> stringResource(R.string.tool_add_memory)
    ToolKind.MEMORY_EDIT -> stringResource(R.string.tool_edit_memory)
    ToolKind.MEMORY_DELETE -> stringResource(R.string.tool_delete_memory)
    ToolKind.MEMORY_UPDATE_ACTIVE -> stringResource(R.string.tool_update_active_memory)
    ToolKind.SKILL_LIST -> stringResource(R.string.tool_list_skills)
    ToolKind.SKILL_READ -> stringResource(R.string.tool_read_skill)
    ToolKind.SKILL_CREATE -> stringResource(R.string.tool_create_skill)
    ToolKind.SKILL_EDIT -> stringResource(R.string.tool_edit_skill)
    ToolKind.SKILL_DELETE -> stringResource(R.string.tool_delete_skill)
    ToolKind.WEB_SEARCH -> when (toolName) {
        "openai_search" -> stringResource(R.string.openai_search)
        "google_search" -> stringResource(R.string.google_search)
        else -> stringResource(R.string.tool_web_search)
    }
    ToolKind.WEB_FETCH -> stringResource(R.string.tool_web_fetch)
    ToolKind.CONVERSATION_SEARCH -> when (toolName) {
        "search_current_conversation" -> stringResource(R.string.tool_search_current_conversation)
        else -> stringResource(R.string.tool_search_conversations)
    }
    ToolKind.CONVERSATION_LIST -> stringResource(R.string.tool_list_conversations)
    ToolKind.CONVERSATION_READ -> stringResource(R.string.tool_read_conversation)
    ToolKind.SHELL_LIST -> stringResource(R.string.tool_list_remote_shells)
    ToolKind.SHELL_EXECUTE -> when (toolName) {
        "execute_remote_command" -> stringResource(R.string.tool_execute_remote_command)
        "execute_local_command" -> stringResource(R.string.tool_execute_local_command)
        "execute_remote_async_command" -> stringResource(R.string.tool_execute_remote_async_command)
        "execute_local_async_command" -> stringResource(R.string.tool_execute_local_async_command)
        "start_remote_process" -> stringResource(R.string.tool_start_remote_process)
        "start_local_process" -> stringResource(R.string.tool_start_local_process)
        "request_all_files_access" -> stringResource(R.string.tool_request_all_files_access)
        else -> stringResource(R.string.tool_execute_shell)
    }
    ToolKind.SHELL_JOB_LIST -> when (toolName) {
        "list_background_processes" -> stringResource(R.string.tool_list_background_processes)
        else -> stringResource(R.string.tool_list_remote_jobs)
    }
    ToolKind.SHELL_JOB_GET -> when (toolName) {
        "get_async_command" -> stringResource(R.string.tool_get_async_command)
        "get_process" -> stringResource(R.string.tool_get_process)
        else -> stringResource(R.string.tool_get_remote_job)
    }
    ToolKind.SHELL_JOB_WAIT -> when (toolName) {
        "wait_async_command" -> stringResource(R.string.tool_wait_async_command)
        "wait_process" -> stringResource(R.string.tool_wait_process)
        else -> stringResource(R.string.tool_wait_remote_job)
    }
    ToolKind.SHELL_JOB_STOP -> when (toolName) {
        "stop_async_command" -> stringResource(R.string.tool_stop_async_command)
        "stop_process" -> stringResource(R.string.tool_stop_process)
        else -> stringResource(R.string.tool_stop_remote_job)
    }
    ToolKind.FILE_READ -> when (toolName) {
        "download_file" -> stringResource(R.string.tool_download_file)
        "read_local_file" -> stringResource(R.string.tool_read_local_file)
        "read_remote_file" -> stringResource(R.string.tool_read_remote_file)
        else -> stringResource(R.string.tool_file_read)
    }
    ToolKind.FILE_WRITE -> when (toolName) {
        "upload_file_to_server" -> stringResource(R.string.tool_upload_file)
        "write_local_file" -> stringResource(R.string.tool_write_local_file)
        "write_remote_file" -> stringResource(R.string.tool_write_remote_file)
        else -> stringResource(R.string.tool_file_write)
    }
    ToolKind.FILE_EDIT -> when (toolName) {
        "str_replace_local" -> stringResource(R.string.tool_str_replace_local)
        "str_replace_remote" -> stringResource(R.string.tool_str_replace_remote)
        else -> stringResource(R.string.tool_file_edit)
    }
    ToolKind.FILE_GLOB -> when (toolName) {
        "list_local_files" -> stringResource(R.string.tool_list_local_files)
        "list_remote_files" -> stringResource(R.string.tool_list_remote_files)
        "glob_local_files" -> stringResource(R.string.tool_glob_local_files)
        "glob_remote_files" -> stringResource(R.string.tool_glob_remote_files)
        else -> stringResource(R.string.tool_file_glob)
    }
    ToolKind.FILE_GREP -> when (toolName) {
        "grep_local" -> stringResource(R.string.tool_grep_local)
        "grep_remote" -> stringResource(R.string.tool_grep_remote)
        else -> stringResource(R.string.tool_file_grep)
    }
    ToolKind.FILE_OPEN -> stringResource(R.string.tool_open_file)
    ToolKind.IMAGE_VIEW -> stringResource(R.string.tool_view_image)
    ToolKind.IMAGE_GENERATE -> stringResource(R.string.tool_generate_image)
    ToolKind.TASK_CREATE -> stringResource(R.string.tool_create_task)
    ToolKind.TASK_LIST -> stringResource(R.string.tool_list_tasks)
    ToolKind.TASK_DELETE -> stringResource(R.string.tool_delete_task)
    ToolKind.LOOP_START -> stringResource(R.string.tool_start_loop)
    ToolKind.LOOP_STOP -> stringResource(R.string.tool_stop_loop)
    ToolKind.MCP -> "MCP"
    ToolKind.UNKNOWN -> if (toolName == "code_execution") {
        stringResource(R.string.code_execution)
    } else {
        toolName
            .ifBlank { stringResource(R.string.tool_context) }
            .split("_")
            .joinToString(" ") { word ->
                word.replaceFirstChar { char -> char.uppercaseChar() }
            }
    }
}

@Composable
internal fun toolSummary(segment: MessageSegment): String {
    return toolSummary(ToolPresentationResolver.resolve(segment))
}

@Composable
internal fun toolSummary(presentation: ToolPresentation): String =
    toolSummaryText(LocalContext.current, presentation)

internal sealed interface ShellPresentationStatus {
    data object Executing : ShellPresentationStatus
    data class Background(val jobId: String?) : ShellPresentationStatus
    data class Exit(val code: Int?) : ShellPresentationStatus
}

internal fun shellPresentationStatus(presentation: ToolPresentation): ShellPresentationStatus =
    when {
        presentation.state == ToolPresentationState.BACKGROUND_RUNNING ->
            ShellPresentationStatus.Background(presentation.jobId)
        presentation.isActive -> ShellPresentationStatus.Executing
        else -> ShellPresentationStatus.Exit(presentation.exitCode)
    }

@Composable
internal fun shellToolSummary(presentation: ToolPresentation): String =
    shellToolSummaryText(LocalContext.current, presentation)

@Composable
internal fun shellExecutionSummary(presentation: ToolPresentation): String =
    shellExecutionSummaryText(LocalContext.current, presentation)

internal fun singleLineShellCommand(
    command: String?,
    maxCharacters: Int = 120,
): String? = normalizeToolSummarySubject(command, maxCharacters)
