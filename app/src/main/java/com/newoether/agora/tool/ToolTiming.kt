package com.newoether.agora.tool

import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.ToolExecutionStates
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Connect/command timings for the detail panel and the model-facing tool JSON. */
object ToolTiming {
    const val CONNECT_MS_KEY = "connect_ms"
    const val COMMAND_MS_KEY = "command_ms"

    val SSH_CONNECT_TOOLS = setOf(
        "execute_remote_command",
        ProcessTools.START_REMOTE,
        "read_remote_file",
        "grep_remote",
        "glob_remote_files",
        "list_remote_files",
        "str_replace_remote",
        "write_remote_file",
        "upload_file_to_server",
        "download_file",
    )

    fun isSshConnectTool(name: String?): Boolean = name in SSH_CONNECT_TOOLS

    fun defersExecUntilProcessExits(name: String?): Boolean =
        name in ProcessTools.START_NAMES || name in ProcessTools.LEGACY_START_NAMES

    fun mergeConnectMs(current: Long?, incoming: Long?): Long? = incoming ?: current

    fun mergeExecMs(current: Long?, incoming: Long?): Long? = incoming ?: current

    fun completedExecMs(
        toolName: String?,
        currentExecMs: Long?,
        startedAtMs: Long?,
        nowMs: Long,
        finalState: String,
    ): Long? {
        if (currentExecMs != null) return currentExecMs
        if (defersExecUntilProcessExits(toolName) && finalState == ToolExecutionStates.BACKGROUND_RUNNING) {
            return null
        }
        val startedAt = startedAtMs ?: return null
        return (nowMs - startedAt).coerceAtLeast(0L)
    }

    fun attachToResult(
        text: String,
        toolName: String?,
        connectMs: Long?,
        commandMs: Long?,
    ): String {
        if (!isSshConnectTool(toolName)) return text
        if (connectMs == null && commandMs == null) return text
        val obj = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return text
        return buildJsonObject {
            obj.forEach { (key, value) ->
                if (key != CONNECT_MS_KEY && key != COMMAND_MS_KEY) put(key, value)
            }
            connectMs?.let { put(CONNECT_MS_KEY, it) }
            commandMs?.let { put(COMMAND_MS_KEY, it) }
        }.toString()
    }

    fun format(ms: Long): String {
        val clamped = ms.coerceAtLeast(0L)
        return when {
            clamped < 1_000L -> "${clamped}ms"
            clamped < 10_000L -> {
                val tenths = (clamped + 50L) / 100L
                "${tenths / 10}.${tenths % 10}s"
            }
            clamped < 60_000L -> "${(clamped + 500L) / 1_000L}s"
            else -> {
                val totalSec = (clamped + 500L) / 1_000L
                val hours = totalSec / 3_600L
                val minutes = (totalSec % 3_600L) / 60L
                val seconds = totalSec % 60L
                when {
                    hours > 0L && minutes == 0L && seconds == 0L -> "${hours}h"
                    hours > 0L && seconds == 0L -> "${hours}h ${minutes}m"
                    hours > 0L -> "${hours}h ${minutes}m ${seconds.toString().padStart(2, '0')}s"
                    else -> "${minutes}m ${seconds.toString().padStart(2, '0')}s"
                }
            }
        }
    }

    fun lines(segment: MessageSegment): List<Line> {
        val connectMs = segment.toolConnectMs
        val execMs = segment.toolExecMs
        val ssh = isSshConnectTool(segment.toolName) || connectMs != null
        val live = isLive(segment.toolState)
        return buildList {
            if (ssh) {
                when {
                    connectMs != null -> add(Line(Kind.CONNECT, connectMs))
                    live -> add(Line(Kind.CONNECT, ms = null))
                }
                when {
                    execMs != null -> add(Line(Kind.COMMAND, execMs))
                    live && connectMs != null -> add(Line(Kind.COMMAND, ms = null))
                }
            } else if (execMs != null) {
                add(Line(Kind.DURATION, execMs))
            } else if (live) {
                add(Line(Kind.DURATION, ms = null))
            }
        }
    }

    data class Line(val kind: Kind, val ms: Long?) {
        val pending: Boolean get() = ms == null
    }

    enum class Kind { CONNECT, COMMAND, DURATION }

    private fun isLive(state: String?): Boolean =
        state == ToolExecutionStates.CALLING || state == ToolExecutionStates.RUNNING
}

internal data class TimedToolOutcome(
    val text: String,
    val connectMs: Long? = null,
    val execMs: Long? = null,
)
