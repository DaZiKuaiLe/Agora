package com.newoether.agora.tool

import com.newoether.agora.data.wireSystemNotice
import com.newoether.agora.data.wireTimeLine
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.ToolExecutionStates
import com.newoether.agora.util.Constants
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.ConcurrentHashMap

internal data class ProcessCardSettlement(
    val result: String,
    val toolState: String,
)

/**
 * Display overlay for start/wait/get cards after a background process exits.
 * Protocol `result_*` rows are not rewritten; this only patches visible segments.
 */
internal object ProcessCardOverlay {
    private val json = Json { ignoreUnknownKeys = true }
    private val settlements = ConcurrentHashMap<String, ProcessCardSettlement>()

    fun put(processId: String, settlement: ProcessCardSettlement) {
        val id = processId.trim()
        if (id.isNotEmpty()) settlements[id] = settlement
    }

    fun get(processId: String): ProcessCardSettlement? = settlements[processId.trim()]

    fun apply(segment: MessageSegment): MessageSegment {
        if (segment.type != "tool") return segment
        if (segment.toolName !in ProcessTools.CARD_UPDATE_NAMES) return segment
        val processId = processIdOf(segment) ?: return segment
        val settlement = settlements[processId] ?: return segment
        if (!isOpenBackgroundCard(segment)) return segment
        return segment.copy(
            toolResult = settlement.result,
            toolState = settlement.toolState,
            toolProgress = null,
        )
    }

    fun processIdOf(segment: MessageSegment): String? =
        processIdIn(segment.toolResult) ?: processIdIn(segment.toolArgs)

    fun isOpenBackgroundCard(segment: MessageSegment): Boolean {
        if (segment.toolName !in ProcessTools.CARD_UPDATE_NAMES) return false
        if (segment.toolState == ToolExecutionStates.BACKGROUND_RUNNING) return true
        return runningFlag(segment.toolResult) == true
    }

    internal fun resetForTests() {
        settlements.clear()
    }

    private fun processIdIn(raw: String?): String? {
        val obj = parseObject(raw) ?: return null
        return primitive(obj, ProcessTools.PROCESS_ID)
            ?: primitive(obj, "async_command_id")
    }

    private fun runningFlag(raw: String?): Boolean? {
        val obj = parseObject(raw) ?: return null
        return (obj["running"] as? JsonPrimitive)?.booleanOrNull
    }

    private fun parseObject(raw: String?): JsonObject? {
        if (raw.isNullOrBlank()) return null
        return runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
    }

    private fun primitive(obj: JsonObject, key: String): String? =
        (obj[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
}

internal fun processCardStateForExit(exitCode: Int): String =
    if (exitCode == 0) ToolExecutionStates.SUCCEEDED else ToolExecutionStates.FAILED

internal fun formatBackgroundProcessCompletedMessage(
    processId: String,
    command: String,
    server: String,
    exitCode: Int,
    stdout: String,
    stderr: String,
    error: String? = null,
    nowMs: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val ok = exitCode == 0 && error.isNullOrBlank()
    val lines = mutableListOf(
        wireTimeLine(nowMs, zone),
        "process: 后台进程已结束",
        "process_id: ${processId.trim()}",
        "command: ${command.replace(Regex("\\s*\\n\\s*"), " ").trim()}",
        "server: ${noticeServerLabel(server)}",
        "exit_code: $exitCode",
        "ok: $ok",
    )
    if (!error.isNullOrBlank()) lines += "error: ${error.trim()}"
    val out = stdout.take(Constants.MAX_TOOL_RESULT_LENGTH)
    val err = stderr.take(Constants.MAX_TOOL_RESULT_LENGTH)
    return buildString {
        append(wireSystemNotice(lines))
        if (out.isNotEmpty()) {
            append("\n\noutput:\n")
            append(out)
        }
        if (err.isNotEmpty()) {
            append("\n\nstderr:\n")
            append(err)
        }
    }
}

internal fun noticeServerLabel(server: String): String = when (val label = server.trim()) {
    "", "Local", "Local Sandbox" -> "local"
    else -> label
}
