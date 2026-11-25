package com.newoether.agora.tool

import android.content.Context
import android.os.SystemClock
import com.newoether.agora.data.ShellDeviceConfig
import com.newoether.agora.util.SshSessionPool
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

internal class FileToolExecutor(
    private val context: Context?,
    private val resolveDevice: (String, GenerationContext) -> ShellDeviceConfig?,
    private val serverNotFound: (String, GenerationContext) -> String,
    private val confirm: suspend (device: ShellDeviceConfig?, summary: String, shared: Boolean) -> Boolean,
    private val persistDetectedShell: ((deviceId: String, detected: String) -> Unit)?,
    private val sessions: SshSessionPool = SshSessionPool.shared,
) {
    private val locator = LocalFileLocator(context)
    private val localAccess = LocalFileAccess(locator)

    suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        val args = parseToolArgs(arguments)
        return when (name) {
            "read_local_file" -> readLocal(args)
            "grep_local" -> grepLocal(args)
            "glob_local_files" -> globLocal(args)
            "list_local_files" -> listLocal(args)
            "str_replace_local" -> replaceLocal(args)
            "write_local_file" -> writeLocal(args)
            in FileToolDefinitions.REMOTE_NAMES -> remoteExecute(name, args, ctx).text
            else -> jsonError(name, "Unknown tool: $name")
        }
    }

    fun executeEvents(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): Flow<ToolExecutionEvent> = flow {
        if (name !in FileToolDefinitions.REMOTE_NAMES) {
            emit(ToolExecutionEvent.Completed(execute(name, arguments, ctx)))
            return@flow
        }
        val outcome = remoteExecute(name, parseToolArgs(arguments), ctx) { connectMs ->
            emit(ToolExecutionEvent.Timing(connectMs = connectMs))
        }
        outcome.execMs?.let { emit(ToolExecutionEvent.Timing(execMs = it)) }
        emit(ToolExecutionEvent.Completed(outcome.text))
    }

    private suspend fun readLocal(args: Map<String, kotlinx.serialization.json.JsonElement>): String {
        val path = arg(args, "path")
        if (path.isBlank()) return jsonError("read_local_file", "path is required")
        return localAccess.read(path, intArg(args, "start_line"), intArg(args, "end_line")).toJson("read_local_file")
    }

    private suspend fun grepLocal(args: Map<String, kotlinx.serialization.json.JsonElement>): String {
        val pattern = arg(args, "pattern")
        if (pattern.isBlank()) return jsonError("grep_local", "pattern is required")
        return localAccess.grep(
            pattern,
            arg(args, "path"),
            arg(args, "glob"),
            boolArg(args, "no_ignore"),
            stringListArg(args, "ignore"),
            FileSearchPolicy.pageOffset(intArg(args, "offset")),
        ).toJson("grep_local")
    }

    private suspend fun globLocal(args: Map<String, kotlinx.serialization.json.JsonElement>): String {
        val pattern = arg(args, "pattern")
        if (pattern.isBlank()) return jsonError("glob_local_files", "pattern is required")
        return localAccess.glob(
            pattern,
            arg(args, "path"),
            boolArg(args, "no_ignore"),
            stringListArg(args, "ignore"),
            FileSearchPolicy.pageOffset(intArg(args, "offset")),
        ).toJson("glob_local_files")
    }

    private suspend fun listLocal(args: Map<String, kotlinx.serialization.json.JsonElement>): String =
        localAccess.list(
            arg(args, "path"),
            FileSearchPolicy.pageOffset(intArg(args, "offset")),
            boolArg(args, "dirs_only"),
        ).toJson("list_local_files")

    private suspend fun writeLocal(args: Map<String, kotlinx.serialization.json.JsonElement>): String {
        val path = arg(args, "path")
        if (path.isBlank()) return jsonError("write_local_file", "path is required")
        val file = locator.resolve(path).getOrElse {
            return jsonError("write_local_file", it.message ?: "Invalid path")
        }
        if (!confirm(null, "write $path", locator.isSharedStorage(file))) {
            return jsonError("write_local_file", "denied_by_user: the user declined to write this file")
        }
        return localAccess.write(path, arg(args, "content")).toJson("write_local_file")
    }

    private suspend fun replaceLocal(args: Map<String, kotlinx.serialization.json.JsonElement>): String {
        val path = arg(args, "path")
        if (path.isBlank()) return jsonError("str_replace_local", "path is required")
        val file = locator.resolve(path).getOrElse {
            return jsonError("str_replace_local", it.message ?: "Invalid path")
        }
        if (!confirm(null, "str_replace $path", locator.isSharedStorage(file))) {
            return jsonError("str_replace_local", "denied_by_user: the user declined to edit this file")
        }
        return localAccess.replace(
            path,
            arg(args, "old_string"),
            arg(args, "new_string"),
            boolArg(args, "replace_all"),
        ).toJson("str_replace_local")
    }

    private suspend fun remoteExecute(
        name: String,
        args: Map<String, kotlinx.serialization.json.JsonElement>,
        ctx: GenerationContext,
        onConnected: suspend (Long) -> Unit = {},
    ): TimedToolOutcome {
        val path = arg(args, "path")
        val serverName = arg(args, "server")
        if (name in REMOTE_MUTATE_NAMES && path.isBlank()) {
            return TimedToolOutcome(jsonError(name, "path is required"))
        }
        val device = resolveDevice(serverName, ctx)
            ?: return TimedToolOutcome(
                jsonError(name, serverNotFound(serverName, ctx), server = serverName),
            )
        if (name in REMOTE_MUTATE_NAMES) {
            val summary = if (name == "write_remote_file") "write $path" else "str_replace $path"
            if (!confirm(device, summary, false)) {
                return TimedToolOutcome(
                    jsonError(name, "denied_by_user: the user declined this file change", server = serverName),
                )
            }
        }
        var connectMs: Long? = null
        return try {
            sessions.use(device, persistDetectedShell) { client ->
                connectMs = client.ensureSession()
                onConnected(connectMs)
                val startedAt = SystemClock.elapsedRealtime()
                val text = runRemote(name, args, SftpFileAccess(client))
                TimedToolOutcome(
                    text = text,
                    connectMs = connectMs,
                    execMs = SystemClock.elapsedRealtime() - startedAt,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            TimedToolOutcome(
                text = jsonError(
                    name,
                    error.message ?: "Remote file operation failed",
                    server = device.name,
                ),
                connectMs = connectMs,
            )
        }
    }

    private suspend fun runRemote(
        name: String,
        args: Map<String, kotlinx.serialization.json.JsonElement>,
        access: FileAccess,
    ): String {
        val path = arg(args, "path")
        return when (name) {
            "read_remote_file" ->
                access.read(path, intArg(args, "start_line"), intArg(args, "end_line")).toJson(name)
            "grep_remote" -> {
                val pattern = arg(args, "pattern")
                if (pattern.isBlank()) return jsonError(name, "pattern is required")
                access.grep(
                    pattern,
                    path,
                    arg(args, "glob"),
                    boolArg(args, "no_ignore"),
                    stringListArg(args, "ignore"),
                    FileSearchPolicy.pageOffset(intArg(args, "offset")),
                ).toJson(name)
            }
            "glob_remote_files" -> {
                val pattern = arg(args, "pattern")
                if (pattern.isBlank()) return jsonError(name, "pattern is required")
                access.glob(
                    pattern,
                    path,
                    boolArg(args, "no_ignore"),
                    stringListArg(args, "ignore"),
                    FileSearchPolicy.pageOffset(intArg(args, "offset")),
                ).toJson(name)
            }
            "list_remote_files" ->
                access.list(
                    path,
                    FileSearchPolicy.pageOffset(intArg(args, "offset")),
                    boolArg(args, "dirs_only"),
                ).toJson(name)
            "str_replace_remote" ->
                access.replace(
                    path,
                    arg(args, "old_string"),
                    arg(args, "new_string"),
                    boolArg(args, "replace_all"),
                ).toJson(name)
            "write_remote_file" ->
                access.write(path, arg(args, "content")).toJson(name)
            else -> jsonError(name, "Unknown tool: $name")
        }
    }
}

private val REMOTE_MUTATE_NAMES = setOf("str_replace_remote", "write_remote_file")

private fun FileReadOutcome.toJson(type: String): String {
    error?.let { return jsonError(type, it) }
    return buildJsonObject {
        put("type", type)
        put("path", path)
        put("size", size)
        lineCount?.let { put("line_count", it) }
        put("start_line", startLine)
        put("end_line", endLine)
        put("metadata_only", metadataOnly)
        content?.let { put("content", it) }
        put("ok", true)
    }.toString()
}

private fun FileWriteOutcome.toJson(type: String): String {
    error?.let { return jsonError(type, it) }
    return buildJsonObject {
        put("type", type)
        put("path", path)
        put("bytes_written", bytesWritten)
        put("ok", true)
    }.toString()
}

private fun FileReplaceOutcome.toJson(type: String): String {
    error?.let { message ->
        return buildJsonObject {
            put("type", type)
            put("error", "error")
            put("message", message)
            put("path", path)
            put("occurrences", occurrences)
            putJsonArray("line_numbers") {
                lineNumbers.forEach { add(JsonPrimitive(it)) }
            }
        }.toString()
    }
    return buildJsonObject {
        put("type", type)
        put("path", path)
        put("occurrences", occurrences)
        put("ok", true)
    }.toString()
}

private fun FileGrepOutcome.toJson(type: String): String {
    error?.let { return jsonError(type, it) }
    return buildJsonObject {
        put("type", type)
        put("pattern", pattern)
        putJsonArray("matches") {
            matches.forEach { match ->
                add(
                    buildJsonObject {
                        put("path", match.path)
                        put("line", match.line)
                        put("text", match.text)
                    },
                )
            }
        }
        put("returned", matches.size)
        put("remaining", remaining)
        put("offset", offset)
        put("scanned_files", scannedFiles)
        put("skipped_oversize", skippedOversize)
        put("skipped_binary", skippedBinary)
        put("engine", engine)
        put("incomplete", incomplete)
        put("phase", phase)
        note?.let { put("note", it) }
        if (ignoredDirs.isNotEmpty()) {
            putJsonArray("ignored_dirs") { ignoredDirs.forEach { add(JsonPrimitive(it)) } }
        }
        if (truncated) put("truncated", true)
        truncatedReason?.let { put("truncated_reason", it) }
        if (remainingIsLowerBound) put("remaining_is_lower_bound", true)
        put("ok", true)
    }.toString()
}

private fun FileGlobOutcome.toJson(type: String): String {
    error?.let { return jsonError(type, it) }
    return buildJsonObject {
        put("type", type)
        putJsonArray("paths") { paths.forEach { add(JsonPrimitive(it)) } }
        put("returned", paths.size)
        put("remaining", remaining)
        put("offset", offset)
        put("incomplete", incomplete)
        put("phase", phase)
        note?.let { put("note", it) }
        if (ignoredDirs.isNotEmpty()) {
            putJsonArray("ignored_dirs") { ignoredDirs.forEach { add(JsonPrimitive(it)) } }
        }
        if (truncated) put("truncated", true)
        truncatedReason?.let { put("truncated_reason", it) }
        if (remainingIsLowerBound) put("remaining_is_lower_bound", true)
        put("ok", true)
    }.toString()
}

private fun FileListOutcome.toJson(type: String): String {
    error?.let { return jsonError(type, it) }
    return buildJsonObject {
        put("type", type)
        putJsonArray("entries") {
            entries.forEach { entry ->
                add(
                    buildJsonObject {
                        put("path", entry.path)
                        put("type", entry.type)
                        entry.size?.let { put("size", it) }
                        entry.mtimeMs?.let { put("mtime_ms", it) }
                    },
                )
            }
        }
        put("returned", entries.size)
        put("remaining", remaining)
        put("offset", offset)
        if (truncated) put("truncated", true)
        truncatedReason?.let { put("truncated_reason", it) }
        if (remainingIsLowerBound) put("remaining_is_lower_bound", true)
        put("ok", true)
    }.toString()
}
