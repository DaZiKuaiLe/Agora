package com.newoether.agora.ui.chat.message

import android.content.Context
import androidx.annotation.StringRes
import com.newoether.agora.R

/**
 * Same one-liner the collapsed tool card shows. Used by chat UI and detail-store stubs.
 * Does not include command text: long shell argv would blow up both surfaces.
 */
internal fun toolSummaryText(context: Context, presentation: ToolPresentation): String {
    if (presentation.kind == ToolKind.SHELL_EXECUTE) {
        return shellToolSummaryText(context, presentation)
    }
    val subject = presentation.subject
    return when (presentation.state) {
        ToolPresentationState.FAILED -> when {
            presentation.kind == ToolKind.SHELL_EXECUTE &&
                presentation.exitCode != null &&
                presentation.exitCode != 0 -> context.getString(
                    R.string.tool_shell_returned_exit_code,
                    presentation.exitCode,
                )
            else -> presentation.errorMessage?.take(160)
                ?: context.getString(R.string.tool_call_failed)
        }
        ToolPresentationState.STOPPED -> context.getString(R.string.tool_execution_stopped)
        ToolPresentationState.BACKGROUND_RUNNING ->
            singleLineShellCommand(subject)
                ?: context.getString(R.string.tool_progress_executing)
        ToolPresentationState.CALLING,
        ToolPresentationState.RUNNING -> runningSummaryText(context, presentation, subject)
        ToolPresentationState.EMPTY -> emptySummaryText(context, presentation, subject)
        ToolPresentationState.COMPLETED -> completedSummaryText(context, presentation, subject)
    }
}

internal fun shellToolSummaryText(context: Context, presentation: ToolPresentation): String =
    when (val status = shellPresentationStatus(presentation)) {
        ShellPresentationStatus.Executing -> context.subjectSummary(
            singleLineShellCommand(presentation.subject),
            R.string.tool_executing_shell,
            R.string.tool_progress_executing,
        )
        is ShellPresentationStatus.Background ->
            singleLineShellCommand(presentation.subject)
                ?: context.getString(R.string.tool_progress_executing)
        is ShellPresentationStatus.Exit -> when {
            presentation.state == ToolPresentationState.FAILED && status.code == null ->
                context.getString(R.string.tool_shell_failed)
            status.code != null ->
                context.getString(R.string.tool_shell_returned_code, status.code)
            else -> context.getString(R.string.tool_shell_execution_completed)
        }
    }

internal fun shellExecutionSummaryText(context: Context, presentation: ToolPresentation): String =
    when (val status = shellPresentationStatus(presentation)) {
        ShellPresentationStatus.Executing -> context.getString(R.string.tool_state_executing)
        is ShellPresentationStatus.Background ->
            context.getString(R.string.tool_state_executing)
        is ShellPresentationStatus.Exit -> status.code?.let { code ->
            context.getString(R.string.tool_shell_detail_returned_code, code)
        } ?: context.getString(R.string.tool_shell_detail_executed)
    }

private fun runningSummaryText(
    context: Context,
    presentation: ToolPresentation,
    subject: String?,
): String = when (presentation.kind) {
    ToolKind.MEMORY_LIST -> context.getString(R.string.tool_looking_up_memories)
    ToolKind.MEMORY_READ -> context.subjectSummary(
        subject, R.string.tool_reading_memory, R.string.tool_progress_reading,
    )
    ToolKind.MEMORY_CREATE -> context.subjectSummary(
        subject, R.string.tool_saving_memory, R.string.tool_progress_saving,
    )
    ToolKind.MEMORY_EDIT -> context.subjectSummary(
        subject, R.string.tool_updating_memory, R.string.tool_progress_updating,
    )
    ToolKind.MEMORY_DELETE -> context.subjectSummary(
        subject, R.string.tool_removing_memory, R.string.tool_progress_removing,
    )
    ToolKind.MEMORY_UPDATE_ACTIVE -> context.getString(R.string.tool_updating_active)
    ToolKind.SKILL_LIST -> context.getString(R.string.tool_list_skills)
    ToolKind.SKILL_READ -> context.getString(R.string.tool_read_skill)
    ToolKind.SKILL_CREATE -> context.getString(R.string.tool_create_skill)
    ToolKind.SKILL_EDIT -> context.getString(R.string.tool_edit_skill)
    ToolKind.SKILL_DELETE -> context.getString(R.string.tool_delete_skill)
    ToolKind.WEB_SEARCH -> context.subjectSummary(
        subject, R.string.tool_searching_web, R.string.tool_progress_searching,
    )
    ToolKind.WEB_FETCH -> context.subjectSummary(
        subject, R.string.tool_web_fetching, R.string.tool_progress_fetching,
    )
    ToolKind.CONVERSATION_SEARCH -> context.subjectSummary(
        subject, R.string.tool_searching_for, R.string.tool_progress_searching,
    )
    ToolKind.CONVERSATION_LIST -> context.getString(R.string.tool_listing_conversations)
    ToolKind.CONVERSATION_READ -> context.subjectSummary(
        subject, R.string.tool_reading_conversation_subject, R.string.tool_progress_reading,
    )
    ToolKind.SHELL_LIST -> context.getString(R.string.tool_listing_shells)
    ToolKind.SHELL_EXECUTE -> context.subjectSummary(
        singleLineShellCommand(subject),
        R.string.tool_executing_shell,
        R.string.tool_progress_executing,
    )
    ToolKind.SHELL_JOB_LIST -> context.getString(R.string.tool_listing_shell_jobs)
    ToolKind.SHELL_JOB_WAIT -> context.subjectSummary(
        subject, R.string.tool_waiting_shell_job, R.string.tool_waiting_shell_job_default,
    )
    ToolKind.SHELL_JOB_GET -> context.subjectSummary(
        subject, R.string.tool_reading_shell_job, R.string.tool_progress_reading,
    )
    ToolKind.SHELL_JOB_STOP -> context.subjectSummary(
        subject, R.string.tool_stopping_shell_job, R.string.tool_progress_stopping,
    )
    ToolKind.FILE_READ -> context.subjectSummary(
        subject, R.string.tool_reading_file, R.string.tool_progress_reading,
    )
    ToolKind.FILE_WRITE -> context.subjectSummary(
        subject, R.string.tool_writing_file, R.string.tool_progress_writing,
    )
    ToolKind.FILE_EDIT -> context.subjectSummary(
        subject, R.string.tool_editing_file, R.string.tool_progress_editing,
    )
    ToolKind.FILE_GLOB -> context.subjectSummary(
        subject, R.string.tool_finding_files, R.string.tool_progress_finding,
    )
    ToolKind.FILE_GREP -> context.subjectSummary(
        subject, R.string.tool_searching_file, R.string.tool_progress_searching,
    )
    ToolKind.FILE_OPEN -> context.subjectSummary(
        subject, R.string.tool_opening_file, R.string.tool_progress_opening,
    )
    ToolKind.IMAGE_VIEW -> context.subjectSummary(
        subject, R.string.tool_viewing_image, R.string.tool_progress_viewing,
    )
    ToolKind.IMAGE_GENERATE -> context.subjectSummary(
        subject, R.string.tool_generating_image_subject, R.string.tool_progress_generating,
    )
    ToolKind.TASK_CREATE -> context.subjectSummary(
        subject, R.string.tool_creating_task_subject, R.string.tool_progress_creating,
    )
    ToolKind.TASK_LIST -> context.getString(R.string.tool_listing_tasks)
    ToolKind.TASK_DELETE -> context.subjectSummary(
        subject, R.string.tool_deleting_task_subject, R.string.tool_progress_deleting,
    )
    ToolKind.LOOP_START -> context.getString(R.string.tool_progress_starting)
    ToolKind.LOOP_STOP -> context.getString(R.string.tool_progress_stopping)
    ToolKind.MCP,
    ToolKind.UNKNOWN -> context.getString(R.string.tool_calling_ellipsis)
}

private fun emptySummaryText(
    context: Context,
    presentation: ToolPresentation,
    subject: String?,
): String = when (presentation.kind) {
    ToolKind.MEMORY_LIST -> context.getString(R.string.tool_lookup_default)
    ToolKind.WEB_SEARCH -> context.subjectSummary(
        subject, R.string.tool_web_search_no_result, R.string.tool_web_search_no_result_default,
    )
    ToolKind.CONVERSATION_SEARCH -> context.subjectSummary(
        subject,
        R.string.tool_conversation_search_no_result,
        R.string.tool_conversation_search_no_result_default,
    )
    ToolKind.CONVERSATION_LIST -> context.getString(R.string.tool_listed_no_conversations)
    ToolKind.SHELL_LIST -> context.getString(R.string.tool_shell_list_done)
    ToolKind.SHELL_EXECUTE -> shellExecutionSummaryText(context, presentation)
    ToolKind.SHELL_JOB_LIST -> context.getString(R.string.tool_no_shell_jobs)
    ToolKind.FILE_READ -> context.subjectSummary(
        subject, R.string.tool_read_file_empty, R.string.tool_read_file_empty_default,
    )
    ToolKind.FILE_GLOB -> context.getString(R.string.tool_found_no_files)
    ToolKind.FILE_GREP -> context.getString(R.string.tool_found_no_matches)
    ToolKind.TASK_LIST -> context.getString(R.string.tool_listed_tasks)
    else -> completedSummaryText(context, presentation, subject)
}

private fun completedSummaryText(
    context: Context,
    presentation: ToolPresentation,
    subject: String?,
): String = when (presentation.kind) {
    ToolKind.MEMORY_LIST -> context.getString(R.string.tool_lookup_count, presentation.count ?: 0)
    ToolKind.SKILL_LIST -> context.getString(R.string.tool_list_skills)
    ToolKind.SKILL_READ -> context.getString(R.string.tool_read_skill)
    ToolKind.SKILL_CREATE -> context.getString(R.string.tool_create_skill)
    ToolKind.SKILL_EDIT -> context.getString(R.string.tool_edit_skill)
    ToolKind.SKILL_DELETE -> context.getString(R.string.tool_delete_skill)
    ToolKind.MEMORY_READ -> context.subjectSummary(
        subject, R.string.tool_read_memory_name, R.string.tool_read_memory_success,
    )
    ToolKind.MEMORY_CREATE -> context.subjectSummary(
        subject, R.string.tool_save_memory_name, R.string.tool_save_memory_default,
    )
    ToolKind.MEMORY_EDIT -> context.subjectSummary(
        subject, R.string.tool_edit_memory_name, R.string.tool_edit_memory_default,
    )
    ToolKind.MEMORY_DELETE -> context.subjectSummary(
        subject, R.string.tool_delete_memory_name, R.string.tool_delete_memory_default,
    )
    ToolKind.MEMORY_UPDATE_ACTIVE -> context.getString(R.string.tool_update_active_default)
    ToolKind.WEB_SEARCH -> if (subject == null || presentation.count == null) {
        context.getString(R.string.tool_web_search_done_default)
    } else {
        context.getString(R.string.tool_web_search_done, presentation.count, subject)
    }
    ToolKind.WEB_FETCH -> context.subjectSummary(
        subject, R.string.tool_web_fetch_done, R.string.tool_web_fetch_done_default,
    )
    ToolKind.CONVERSATION_SEARCH -> if (subject == null) {
        context.getString(R.string.tool_conversation_search_done_default, presentation.count ?: 0)
    } else {
        context.getString(
            R.string.tool_conversation_search_done_for,
            presentation.count ?: 0,
            subject,
        )
    }
    ToolKind.CONVERSATION_LIST -> context.getString(
        R.string.tool_listed_conversations,
        presentation.count ?: 0,
    )
    ToolKind.CONVERSATION_READ -> context.subjectSummary(
        subject, R.string.tool_read_conversation_done, R.string.tool_read_conversation_done_default,
    )
    ToolKind.SHELL_LIST -> context.getString(R.string.tool_shell_list_count, presentation.count ?: 0)
    ToolKind.SHELL_EXECUTE -> shellExecutionSummaryText(context, presentation)
    ToolKind.SHELL_JOB_LIST -> context.getString(
        R.string.tool_shell_job_count,
        presentation.count ?: 0,
    )
    ToolKind.SHELL_JOB_WAIT -> context.subjectSummary(
        presentation.jobId ?: subject,
        R.string.tool_waited_shell_job,
        R.string.tool_waited_shell_job_default,
    )
    ToolKind.SHELL_JOB_GET -> context.subjectSummary(
        presentation.jobId ?: subject,
        R.string.tool_shell_job_status,
        R.string.tool_shell_job_read_default,
    )
    ToolKind.SHELL_JOB_STOP -> context.subjectSummary(
        presentation.jobId ?: subject,
        R.string.tool_stopped_shell_job,
        R.string.tool_shell_job_stopped_default,
    )
    ToolKind.FILE_READ -> context.subjectSummary(
        subject, R.string.tool_read_file_done, R.string.tool_read_file_done_default,
    )
    ToolKind.FILE_WRITE -> context.subjectSummary(
        subject, R.string.tool_wrote_file, R.string.tool_wrote_file_default,
    )
    ToolKind.FILE_EDIT -> context.subjectSummary(
        subject, R.string.tool_edited_file, R.string.tool_edited_file_default,
    )
    ToolKind.FILE_GLOB -> context.getString(R.string.tool_found_files, presentation.count ?: 0)
    ToolKind.FILE_GREP -> context.getString(R.string.tool_searched_file, presentation.count ?: 0)
    ToolKind.FILE_OPEN -> context.subjectSummary(
        subject, R.string.tool_opened_file, R.string.tool_opened_file_default,
    )
    ToolKind.IMAGE_VIEW -> context.subjectSummary(
        subject, R.string.tool_viewed_image, R.string.tool_viewed_image_default,
    )
    ToolKind.IMAGE_GENERATE -> context.getString(R.string.tool_generated_image)
    ToolKind.TASK_CREATE -> context.getString(R.string.tool_created_task)
    ToolKind.TASK_LIST -> context.getString(R.string.tool_listed_tasks)
    ToolKind.TASK_DELETE -> context.getString(R.string.tool_deleted_task)
    ToolKind.LOOP_START -> context.getString(R.string.tool_started_loop)
    ToolKind.LOOP_STOP -> context.getString(R.string.tool_stopped_loop)
    ToolKind.MCP,
    ToolKind.UNKNOWN -> context.getString(R.string.tool_done)
}

private fun Context.subjectSummary(
    subject: String?,
    @StringRes withSubject: Int,
    @StringRes withoutSubject: Int,
): String = if (subject.isNullOrBlank()) {
    getString(withoutSubject)
} else {
    getString(withSubject, subject)
}
