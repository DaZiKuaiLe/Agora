package com.newoether.agora.tool

import com.newoether.agora.util.Constants
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

internal fun formatExecResult(
    type: String,
    command: String,
    server: String,
    exitCode: Int,
    stdout: String,
    stderr: String,
    okExit: Set<Int>?,
    detailStore: DetailStore?,
    processId: String? = null,
    running: Boolean = false,
    stopped: Boolean = false,
    ok: Boolean = true,
    error: String? = null,
): String {
    val output = (stdout + if (stderr.isNotBlank()) "\n$stderr" else "").trimEnd()
    if (!running && okExit != null && exitCode in okExit && detailStore != null) {
        val body = buildJsonObject {
            put("type", type)
            put("server", server)
            put("command", command)
            put("exit_code", exitCode)
            put("stdout", stdout)
            put("stderr", stderr)
            put("output", output)
        }.toString()
        val record = detailStore.put(type, "exit $exitCode", body)
        return buildJsonObject {
            put("type", type)
            put("server", server)
            put("command", command)
            put("exit_code", exitCode)
            put("stdout_bytes", stdout.toByteArray().size)
            put("stderr_bytes", stderr.toByteArray().size)
            put("detail_id", record.detailId)
            put("body_omitted", true)
            put("ok", ok)
            processId?.let { put(ProcessTools.PROCESS_ID, it) }
            put("running", false)
            if (stopped) put("stopped", true)
            error?.let { put("error", it) }
        }.toString()
    }
    return buildJsonObject {
        put("type", type)
        put("server", server)
        put("command", command)
        put("exit_code", exitCode)
        put("output", output.take(Constants.MAX_TOOL_RESULT_LENGTH))
        if (stdout.isNotBlank()) put("stdout", stdout.take(Constants.MAX_TOOL_RESULT_LENGTH))
        if (stderr.isNotBlank()) put("stderr", stderr.take(Constants.MAX_TOOL_RESULT_LENGTH))
        processId?.let { put(ProcessTools.PROCESS_ID, it) }
        put("running", running)
        if (stopped) put("stopped", true)
        error?.let { put("error", it) }
        put("ok", ok)
    }.toString()
}

internal fun formatAsyncRunning(
    type: String,
    command: String,
    server: String,
    id: String,
    stdout: String,
    stderr: String,
): String = buildJsonObject {
    put("type", type)
    put("server", server)
    put("command", command)
    put("running", true)
    put(ProcessTools.PROCESS_ID, id)
    if (stdout.isNotBlank()) put("stdout", stdout.take(Constants.MAX_TOOL_RESULT_LENGTH))
    if (stderr.isNotBlank()) put("stderr", stderr.take(Constants.MAX_TOOL_RESULT_LENGTH))
    put("ok", true)
}.toString()

internal fun applyOkExitIfRequested(
    raw: String,
    okExit: Set<Int>?,
    detailStore: DetailStore?,
): String {
    if (okExit == null || detailStore == null) return raw
    val obj = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return raw
    val exit = (obj["exit_code"] as? JsonPrimitive)?.intOrNull ?: return raw
    if (exit !in okExit) return raw
    val type = (obj["type"] as? JsonPrimitive)?.contentOrNull ?: return raw
    return formatExecResult(
        type = type,
        command = obj.string("command").orEmpty(),
        server = obj.string("server").orEmpty(),
        exitCode = exit,
        stdout = obj.string("stdout") ?: obj.string("output").orEmpty(),
        stderr = obj.string("stderr").orEmpty(),
        okExit = okExit,
        detailStore = detailStore,
    )
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull
