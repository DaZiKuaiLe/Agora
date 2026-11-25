package com.newoether.agora.tool

import android.os.SystemClock
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.data.ShellDeviceConfig
import com.newoether.agora.model.ToolCallData
import com.newoether.agora.util.Constants
import com.newoether.agora.util.LocalDownloadTarget
import com.newoether.agora.util.SshRemoteShell
import com.newoether.agora.viewmodel.AllFilesAccessController
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File

class ShellToolProvider(
    @Suppress("UNUSED_PARAMETER") sandboxFactory: com.newoether.agora.sandbox.SandboxManagerFactory? = null,
    private val imageStore: ToolImageStore? = null,
    private val downloadsDir: File? = null,
    private val context: android.content.Context? = null,
    private val detailStore: DetailStore? = null,
) : ToolProvider {

    private val localBackend: Backend? = context?.let { NodeBackend(it) }
    private val fileTools by lazy {
        FileToolExecutor(
            context = context,
            resolveDevice = ::resolveShellDevice,
            serverNotFound = ::serverNotFoundMessage,
            confirm = { device, summary, shared ->
                confirmTarget(device, summary, localSharedStorageExposed = shared)
            },
            persistDetectedShell = persistSshDetectedShell,
        )
    }
    private val asyncTools by lazy {
        AsyncCommandExecutor(
            context = context,
            detailStore = detailStore,
            resolveDevice = ::resolveShellDevice,
            serverNotFound = ::serverNotFoundMessage,
            confirm = { device, summary, shared ->
                confirmTarget(device, summary, localSharedStorageExposed = shared)
            },
            persistDetectedShell = persistSshDetectedShell,
        )
    }

    /**
     * Optional user-confirmation gate for state-changing operations. Local commands and scripts are
     * gated because they mutate app-private (or shared) storage.
     */
    var confirm: (suspend (server: String, summary: String) -> Boolean)? = null

    /** Optional handshake that shows the all-files access prompt and awaits the user's outcome. */
    var requestAllFilesAccess: (suspend (reason: String?) -> AllFilesAccessController.Outcome)? = null

    /** Persist auto-detected SSH exec shell onto the matching device config. */
    var persistSshDetectedShell: ((deviceId: String, detected: String) -> Unit)? = null

    /** No-op retained for GenerationToolExecutor call sites (Conch job ack removed). */
    @Suppress("UNUSED_PARAMETER")
    internal suspend fun acknowledgeCommittedJobs(
        calls: List<ToolCallData>,
        context: GenerationContext,
    ) = Unit

    private suspend fun confirmTarget(
        device: ShellDeviceConfig?,
        summary: String,
        localSharedStorageExposed: Boolean = false,
        targetLabel: String? = null,
    ): Boolean {
        if (device == null && !localSharedStorageExposed) return true
        val target = targetLabel
            ?: device?.name?.ifBlank { "${device.type} server" }
            ?: "Local"
        return confirm?.invoke(target, summary) ?: true
    }

    // ── Helpers ────────────────────────────────────────────

    private fun resolveShellDevice(serverName: String, ctx: GenerationContext): ShellDeviceConfig? {
        val sshDevices = ctx.shellDevices.filter { it.type == "ssh" }
        return if (serverName.isNotBlank()) {
            sshDevices.find { it.name.equals(serverName, ignoreCase = true) }
        } else if (sshDevices.size == 1) {
            sshDevices.first()
        } else null
    }

    private fun serverNotFoundMessage(serverName: String, ctx: GenerationContext): String {
        val allNames = ctx.shellDevices
            .filter { it.type == "ssh" }
            .map { "\"${it.name.ifBlank { "Untitled" }}\"" }
        return if (allNames.size == 1) {
            "Unknown remote terminal: $serverName. Use ${allNames[0]} or omit the server parameter."
        } else if (allNames.isEmpty()) {
            "No remote terminals are configured."
        } else {
            val names = allNames.joinToString(", ")
            if (serverName.isBlank()) {
                "Multiple remote terminals available. Use list_remote_shells to see them, then specify one: $names."
            } else {
                "Unknown remote terminal: $serverName. Available: $names."
            }
        }
    }

    private fun getBackend(serverName: String, ctx: GenerationContext): SshBackend? {
        val device = resolveShellDevice(serverName, ctx) ?: return null
        return SshBackend(device).also { backend ->
            backend.persistDetectedShell = persistSshDetectedShell
        }
    }

    // ── ToolProvider interface ─────────────────────────────

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> =
        ShellToolDefinitions.build(
            ctx = ctx,
            packagedCliNames = context?.let { LocalToolchain.availableNames(it) }.orEmpty(),
        ) + FileToolDefinitions.build(ctx) + AsyncCommandToolDefinitions.build(ctx)

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        return when (name) {
            "list_remote_shells" -> listRemoteShells(ctx)
            "execute_remote_command" -> executeRemoteCommand(arguments, ctx)
            "execute_local_command" -> executeLocalCommand(arguments, ctx)
            "request_all_files_access" -> executeRequestAllFilesAccess(arguments)
            "read_image" -> executeReadImage(arguments).text
            OpenFile.TOOL -> executeOpenFile(arguments).text
            "upload_file_to_server" -> executeUploadFile(arguments, ctx).text
            "download_file" -> executeDownloadFile(arguments, ctx).result.text
            in FileToolDefinitions.NAMES -> fileTools.execute(name, arguments, ctx)
            in AsyncCommandToolDefinitions.NAMES -> asyncTools.execute(name, arguments, ctx)
            else -> "Unknown tool: $name"
        }
    }

    override fun executeEvents(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): Flow<ToolExecutionEvent> {
        return when (name) {
            "execute_remote_command" -> executeRemoteCommandEvents(arguments, ctx)
            "execute_local_command" -> flow {
                emit(ToolExecutionEvent.Completed(executeLocalCommand(arguments, ctx)))
            }
            "read_image" -> flow {
                emit(ToolExecutionEvent.Completed(executeReadImage(arguments)))
            }
            OpenFile.TOOL -> flow {
                emit(ToolExecutionEvent.Completed(executeOpenFile(arguments)))
            }
            "upload_file_to_server" -> flow {
                val outcome = executeUploadFile(arguments, ctx) { connectMs ->
                    emit(ToolExecutionEvent.Timing(connectMs = connectMs))
                }
                outcome.execMs?.let { emit(ToolExecutionEvent.Timing(execMs = it)) }
                emit(ToolExecutionEvent.Completed(outcome.text))
            }
            "download_file" -> flow {
                val outcome = executeDownloadFile(arguments, ctx) { connectMs ->
                    emit(ToolExecutionEvent.Timing(connectMs = connectMs))
                }
                outcome.execMs?.let { emit(ToolExecutionEvent.Timing(execMs = it)) }
                emit(ToolExecutionEvent.Completed(outcome.result))
            }
            in FileToolDefinitions.REMOTE_NAMES -> fileTools.executeEvents(name, arguments, ctx)
            ProcessTools.START_REMOTE -> asyncTools.executeEvents(name, arguments, ctx)
            else -> super<ToolProvider>.executeEvents(name, arguments, ctx)
        }
    }

    override fun handles(name: String): Boolean = name in setOf(
        "list_remote_shells", "execute_remote_command", "execute_local_command",
        "request_all_files_access", "read_image", OpenFile.TOOL,
        "upload_file_to_server", "download_file",
    ) || name in FileToolDefinitions.NAMES || name in AsyncCommandToolDefinitions.NAMES

    // ── list_remote_shells ─────────────────────────────────

    private suspend fun listRemoteShells(ctx: GenerationContext): String {
        val items = ctx.shellDevices.filter { it.type == "ssh" }.map { d ->
            val fp = SshRemoteShell.fingerprint(
                preference = d.sshShell,
                detected = d.sshDetectedShell,
            )
            buildJsonObject {
                put("name", d.name.ifBlank { "Untitled" })
                put("description", d.description)
                put("type", d.type)
                put("host", d.sshHost)
                put("port", d.sshPort)
                put("shell_preference", fp.shellPreference)
                put("shell", fp.shell)
                put("command_syntax", fp.commandSyntax)
                put("os_hint", fp.osHint)
            }
        }
        return buildJsonObject {
            put("type", "list_remote_shells")
            putJsonArray("devices") { items.forEach { add(it) } }
        }.toString()
    }

    // ── Local execution ────────────────────────────────────

    private suspend fun executeRequestAllFilesAccess(arguments: String): String {
        val args = parseToolArgs(arguments)
        val reason = arg(args, "reason").ifBlank { null }
        val outcome = requestAllFilesAccess?.invoke(reason)
            ?: AllFilesAccessController.Outcome.UNAVAILABLE
        val status = when (outcome) {
            AllFilesAccessController.Outcome.GRANTED -> "granted"
            AllFilesAccessController.Outcome.DENIED -> "denied"
            AllFilesAccessController.Outcome.DISMISSED -> "dismissed"
            AllFilesAccessController.Outcome.UNAVAILABLE -> "unavailable"
        }
        return buildJsonObject {
            put("type", "request_all_files_access")
            put("status", status)
            when (outcome) {
                AllFilesAccessController.Outcome.GRANTED ->
                    put("message", "All-files access is granted. Retry the shared-storage operation.")
                AllFilesAccessController.Outcome.DENIED ->
                    put(
                        "message",
                        "User returned without granting all-files access. Shared storage remains unavailable.",
                    )
                AllFilesAccessController.Outcome.DISMISSED ->
                    put(
                        "message",
                        "User dismissed the permission prompt. Ask before requesting again, or use app-private paths.",
                    )
                AllFilesAccessController.Outcome.UNAVAILABLE ->
                    put(
                        "message",
                        "Cannot show the permission prompt right now (no foreground UI or no handler). Ask the user to open Agora and grant all-files access in Remote Terminals settings.",
                    )
            }
            if (reason != null) put("reason", reason)
        }.toString()
    }

    private suspend fun executeLocalCommand(arguments: String, ctx: GenerationContext): String {
        val args = parseToolArgs(arguments)
        val command = arg(args, "command")
        if (command.isBlank()) return jsonError("execute_local_command", "no_command")
        val timeoutMs = arg(args, "timeout_ms").toIntOrNull()
            ?.coerceIn(1000, Constants.TOOL_EXECUTION_TIMEOUT_MS.toInt())
            ?: Constants.TOOL_EXECUTION_TIMEOUT_MS.toInt()
        val workdir = arg(args, "workdir")
        val backend = localBackend
            ?: return jsonError(
                "execute_local_command",
                "Local command backend is not available. Enable the local command tool first.",
            )
        return try {
            if (!confirmTarget(
                    backend.device,
                    "$ $command",
                    localSharedStorageExposed = backend.device == null,
                    targetLabel = "Local",
                )
            ) {
                return jsonError(
                    "execute_local_command",
                    "denied_by_user: the user declined to run this command",
                )
            }
            applyOkExitIfRequested(
                backend.executeCommand(command, workdir, timeoutMs),
                intSetArg(args, "ok_exit"),
                detailStore,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            jsonError("execute_local_command", e.message ?: "Local command failed")
        } finally {
            backend.close()
        }
    }

    // ── Remote execution ───────────────────────────────────

    private suspend fun executeRemoteCommand(arguments: String, ctx: GenerationContext): String {
        val args = parseToolArgs(arguments)
        val command = arg(args, "command")
        if (command.isBlank()) return jsonError("execute_remote_command", "no_command")
        val serverName = arg(args, "server")
        val foregroundMaxMs = Constants.TOOL_EXECUTION_TIMEOUT_MS.toInt()
        val rawTimeout = arg(args, "timeout_ms")
        if (rawTimeout.isBlank()) {
            return jsonError(
                "execute_remote_command",
                "timeout_ms is required",
                server = serverName,
                command = command,
            )
        }
        val timeoutMs = (rawTimeout.toIntOrNull()
            ?: return jsonError(
                "execute_remote_command",
                "timeout_ms must be an integer, got \"$rawTimeout\"",
                server = serverName,
                command = command,
            )).coerceIn(1000, foregroundMaxMs)
        val workdir = arg(args, "workdir")

        val backend = getBackend(serverName, ctx)
            ?: return jsonError("execute_remote_command", serverNotFoundMessage(serverName, ctx))
        return try {
            if (!confirmTarget(
                    backend.device,
                    "$ $command",
                    localSharedStorageExposed = false,
                )
            ) {
                return jsonError(
                    "execute_remote_command",
                    "denied_by_user: the user declined to run this command",
                    server = serverName,
                    command = command,
                )
            }
            applyOkExitIfRequested(
                backend.executeCommand(command, workdir, timeoutMs),
                intSetArg(args, "ok_exit"),
                detailStore,
            )
        } finally {
            backend.close()
        }
    }

    private fun executeRemoteCommandEvents(
        arguments: String,
        ctx: GenerationContext,
    ): Flow<ToolExecutionEvent> = flow {
        val args = parseToolArgs(arguments)
        val command = arg(args, "command")
        if (command.isBlank()) {
            emit(ToolExecutionEvent.Completed(jsonError("execute_remote_command", "no_command")))
            return@flow
        }
        val serverName = arg(args, "server")
        val foregroundMaxMs = Constants.TOOL_EXECUTION_TIMEOUT_MS.toInt()
        val rawTimeout = arg(args, "timeout_ms")
        if (rawTimeout.isBlank()) {
            emit(
                ToolExecutionEvent.Completed(
                    jsonError(
                        "execute_remote_command",
                        "timeout_ms is required",
                        server = serverName,
                        command = command,
                    ),
                ),
            )
            return@flow
        }
        val timeoutMs = (rawTimeout.toIntOrNull()
            ?: run {
                emit(
                    ToolExecutionEvent.Completed(
                        jsonError(
                            "execute_remote_command",
                            "timeout_ms must be an integer, got \"$rawTimeout\"",
                            server = serverName,
                            command = command,
                        ),
                    ),
                )
                return@flow
            }).coerceIn(1000, foregroundMaxMs)
        val workdir = arg(args, "workdir")
        val backend = getBackend(serverName, ctx)
        if (backend == null) {
            emit(
                ToolExecutionEvent.Completed(
                    jsonError("execute_remote_command", serverNotFoundMessage(serverName, ctx)),
                ),
            )
            return@flow
        }
        emit(
            ToolExecutionEvent.TargetResolved(
                backend.device.name.ifBlank { "Untitled" },
            ),
        )
        try {
            if (!confirmTarget(
                    backend.device,
                    "$ $command",
                    localSharedStorageExposed = false,
                )
            ) {
                emit(
                    ToolExecutionEvent.Completed(
                        jsonError(
                            "execute_remote_command",
                            "denied_by_user: the user declined to run this command",
                            server = serverName,
                            command = command,
                        ),
                    ),
                )
                return@flow
            }
            if (backend.device.sshHost.isBlank()) {
                emit(
                    ToolExecutionEvent.Completed(
                        jsonError(
                            "execute_remote_command",
                            "SSH device \"${backend.device.name}\" has no host configured.",
                            server = serverName,
                            command = command,
                        ),
                    ),
                )
                return@flow
            }
            val connectMs = try {
                backend.ensureSession()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                emit(
                    ToolExecutionEvent.Completed(
                        jsonError(
                            "execute_remote_command",
                            error.message ?: "SSH connection failed",
                            server = serverName,
                            command = command,
                        ),
                    ),
                )
                return@flow
            }
            emit(ToolExecutionEvent.Timing(connectMs = connectMs))
            emit(ToolExecutionEvent.Progress("Running command"))
            val startedAt = SystemClock.elapsedRealtime()
            backend.executeCommandEvents(command, workdir, timeoutMs).collect { event ->
                if (event is ToolExecutionEvent.Completed) {
                    emit(ToolExecutionEvent.Timing(execMs = SystemClock.elapsedRealtime() - startedAt))
                }
                emit(event)
            }
        } finally {
            backend.close()
        }
    }

    private fun executeOpenFile(arguments: String): ToolExecutionResult {
        val path = arg(parseToolArgs(arguments), "path")
        val opened = OpenFile.launch(context, path)
            .getOrElse {
                return ToolExecutionResult(
                    text = jsonError(OpenFile.TOOL, it.message ?: "Failed to open"),
                    isError = true,
                )
            }
        return ToolExecutionResult(
            text = buildJsonObject {
                put("type", OpenFile.TOOL)
                put("path", opened.path)
                put("kind", opened.kind)
                put("mime", opened.mime)
                put("ok", true)
            }.toString(),
        )
    }

    private suspend fun executeReadImage(arguments: String): ToolExecutionResult {
        val args = parseToolArgs(arguments)
        val path = arg(args, "path")
        if (path.isBlank()) {
            return ToolExecutionResult(
                text = jsonError("read_image", "path is required"),
                isError = true,
            )
        }
        val store = imageStore
            ?: return ToolExecutionResult(
                text = jsonError("read_image", "Tool image storage is unavailable"),
                isError = true,
            )
        val file = File(path)
        if (!file.isFile || !file.exists()) {
            return ToolExecutionResult(
                text = jsonError("read_image", "File not found: $path"),
                isError = true,
            )
        }
        return try {
            val bytes = withContext(Dispatchers.IO) { file.readBytes() }
            val mime = mimeTypeFor(file) ?: "image/jpeg"
            val attachment = withContext(Dispatchers.IO) {
                store.persistBytes(
                    bytes = bytes,
                    mimeType = mime,
                    filePrefix = "local",
                )
            }
            ToolExecutionResult(
                text = buildJsonObject {
                    put("type", "read_image")
                    put("path", path)
                    put("mime_type", attachment.mimeType)
                    put("size", attachment.sizeBytes)
                    attachment.width?.let { put("width", it) }
                    attachment.height?.let { put("height", it) }
                    put("ok", true)
                }.toString(),
                images = listOf(attachment),
                transcribeImages = true,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ToolExecutionResult(
                text = jsonError("read_image", error.message ?: "Failed to load image"),
                isError = true,
            )
        }
    }

    private fun mimeTypeFor(file: File): String? = when (file.extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "heic", "heif" -> "image/heic"
        "avif" -> "image/avif"
        "bmp" -> "image/bmp"
        else -> null
    }

    // ── SFTP transfer ──────────────────────────────────────

    private suspend fun executeUploadFile(
        arguments: String,
        ctx: GenerationContext,
        onConnected: suspend (Long) -> Unit = {},
    ): TimedToolOutcome {
        val args = parseToolArgs(arguments)
        val localPath = arg(args, "local_path")
        if (localPath.isBlank()) {
            return TimedToolOutcome(jsonError("upload_file_to_server", "local_path is required"))
        }
        LocalDownloadTarget.uploadSourceReadable(localPath, context).exceptionOrNull()?.let {
            return TimedToolOutcome(
                jsonError("upload_file_to_server", it.message ?: "Cannot read local_path"),
            )
        }
        val remotePath = arg(args, "remote_path")
        if (remotePath.isBlank()) {
            return TimedToolOutcome(jsonError("upload_file_to_server", "remote_path is required"))
        }
        val serverName = arg(args, "server")

        val backend = getBackend(serverName, ctx) ?: return TimedToolOutcome(
            jsonError(
                "upload_file_to_server",
                serverNotFoundMessage(serverName, ctx),
                server = serverName,
            ),
        )
        var connectMs: Long? = null
        return try {
            if (!confirmTarget(backend.device, "upload file: $localPath → $remotePath")) {
                return TimedToolOutcome(
                    jsonError(
                        "upload_file_to_server",
                        "denied_by_user: the user declined to upload this file",
                        server = serverName,
                    ),
                )
            }
            connectMs = backend.ensureSession()
            onConnected(connectMs)
            val startedAt = SystemClock.elapsedRealtime()
            val error = backend.uploadFile(localPath, remotePath)
            val text = error ?: buildJsonObject {
                put("type", "upload_file_to_server")
                put("server", backend.device.name)
                put("local_path", localPath)
                put("remote_path", remotePath)
                put("ok", true)
            }.toString()
            TimedToolOutcome(
                text = text,
                connectMs = connectMs,
                execMs = SystemClock.elapsedRealtime() - startedAt,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            TimedToolOutcome(
                text = jsonError(
                    "upload_file_to_server",
                    error.message ?: "Failed to upload file",
                    server = backend.device.name,
                ),
                connectMs = connectMs,
            )
        } finally {
            backend.close()
        }
    }

    private suspend fun executeDownloadFile(
        arguments: String,
        ctx: GenerationContext,
        onConnected: suspend (Long) -> Unit = {},
    ): TimedShellResult {
        val args = parseToolArgs(arguments)
        val remotePath = arg(args, "remote_path")
        if (remotePath.isBlank()) {
            return TimedShellResult(
                ToolExecutionResult(
                    text = jsonError("download_file", "remote_path is required"),
                    isError = true,
                ),
            )
        }
        val serverName = arg(args, "server")
        val backend = getBackend(serverName, ctx) ?: return TimedShellResult(
            ToolExecutionResult(
                text = jsonError(
                    "download_file",
                    serverNotFoundMessage(serverName, ctx),
                    server = serverName,
                ),
                isError = true,
            ),
        )
        val targetDir = arg(args, "target_dir")
        val baseDownload = downloadsDir
            ?: File(System.getProperty("java.io.tmpdir") ?: ".")
        val localDir = LocalDownloadTarget.resolve(targetDir, baseDownload, context).getOrElse {
            return TimedShellResult(
                ToolExecutionResult(
                    text = jsonError(
                        "download_file",
                        it.message ?: "Invalid target_dir",
                        server = serverName,
                    ),
                    isError = true,
                ),
            )
        }.absolutePath
        var connectMs: Long? = null
        return try {
            if (!confirmTarget(backend.device, "download file: $remotePath → $localDir")) {
                return TimedShellResult(
                    ToolExecutionResult(
                        text = jsonError(
                            "download_file",
                            "denied_by_user: the user declined to download this file",
                            server = serverName,
                        ),
                        isError = true,
                    ),
                )
            }
            connectMs = backend.ensureSession()
            onConnected(connectMs)
            val startedAt = SystemClock.elapsedRealtime()
            val dest = backend.downloadFile(remotePath, localDir)
            val destFile = dest?.let { File(it) }
            val result = if (destFile == null || !destFile.exists()) {
                val detail = dest?.takeIf { it.contains("failed") || it.contains("error") }
                    ?: "SFTP download failed"
                ToolExecutionResult(
                    text = jsonError("download_file", detail, server = backend.device.name),
                    isError = true,
                )
            } else {
                ToolExecutionResult(
                    text = buildJsonObject {
                        put("type", "download_file")
                        put("server", backend.device.name)
                        put("remote_path", remotePath)
                        put("local_path", destFile.absolutePath)
                        put("ok", true)
                    }.toString(),
                )
            }
            TimedShellResult(
                result = result,
                connectMs = connectMs,
                execMs = SystemClock.elapsedRealtime() - startedAt,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            TimedShellResult(
                result = ToolExecutionResult(
                    text = jsonError(
                        "download_file",
                        error.message ?: "Failed to download file",
                        server = backend.device.name,
                    ),
                    isError = true,
                ),
                connectMs = connectMs,
            )
        } finally {
            backend.close()
        }
    }
}

private data class TimedShellResult(
    val result: ToolExecutionResult,
    val connectMs: Long? = null,
    val execMs: Long? = null,
)




