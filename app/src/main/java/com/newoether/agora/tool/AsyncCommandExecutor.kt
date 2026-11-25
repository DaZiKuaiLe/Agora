package com.newoether.agora.tool

import android.content.Context
import android.os.SystemClock
import com.newoether.agora.data.ShellDeviceConfig
import com.newoether.agora.util.SshExecSession
import com.newoether.agora.util.createSshClient
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

internal class AsyncCommandExecutor(
    private val context: Context?,
    private val detailStore: DetailStore?,
    private val resolveDevice: (String, GenerationContext) -> ShellDeviceConfig?,
    private val serverNotFound: (String, GenerationContext) -> String,
    private val confirm: suspend (device: ShellDeviceConfig?, summary: String, shared: Boolean) -> Boolean,
    private val persistDetectedShell: ((deviceId: String, detected: String) -> Unit)?,
) {
    suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        val args = parseToolArgs(arguments)
        return when (name) {
            ProcessTools.START_LOCAL -> startLocal(args, ctx)
            ProcessTools.START_REMOTE -> startRemote(args, ctx).text
            ProcessTools.GET -> peek(args)
            ProcessTools.LIST -> listLive()
            ProcessTools.WAIT -> wait(args)
            ProcessTools.STOP -> stop(args)
            else -> jsonError(name, "Unknown tool: $name")
        }
    }

    fun executeEvents(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): Flow<ToolExecutionEvent> = flow {
        if (name != ProcessTools.START_REMOTE) {
            emit(ToolExecutionEvent.Completed(execute(name, arguments, ctx)))
            return@flow
        }
        val outcome = startRemote(parseToolArgs(arguments), ctx) { connectMs ->
            emit(ToolExecutionEvent.Timing(connectMs = connectMs))
        }
        outcome.execMs?.let { emit(ToolExecutionEvent.Timing(execMs = it)) }
        emit(ToolExecutionEvent.Completed(outcome.text))
    }

    private suspend fun startLocal(
        args: Map<String, kotlinx.serialization.json.JsonElement>,
        ctx: GenerationContext,
    ): String {
        val command = arg(args, "command")
        if (command.isBlank()) return jsonError(ProcessTools.START_LOCAL, "no_command")
        val waitMs = ProcessTools.parseOptionalWaitMs(arg(args, "wait_ms"))
            ?: return waitMsError(ProcessTools.START_LOCAL)
        val workdir = arg(args, "workdir")
        val app = context
            ?: return jsonError(ProcessTools.START_LOCAL, "Local command backend is not available.")
        if (!confirm(null, "$ $command", true)) {
            return jsonError(ProcessTools.START_LOCAL, "denied_by_user: the user declined to run this command")
        }
        val tools = LocalToolchain.resolve(app)
        val rewritten = LocalToolchain.rewriteCommand(command, tools)
        val process = try {
            startLocalProcess(app, rewritten, workdir)
        } catch (error: Exception) {
            return jsonError(ProcessTools.START_LOCAL, error.message ?: "Failed to start shell")
        }
        val id = AsyncCommandId.local()
        val run = LocalAsyncRun(id, command, process)
        AsyncCommandRegistry.put(run, waiting = true, conversationId = ctx.conversationId)
        try {
            return settle(ProcessTools.START_LOCAL, run, waitMs, intSetArg(args, "ok_exit"))
        } finally {
            AsyncCommandRegistry.endWait(run.id.encode())
        }
    }

    private suspend fun startRemote(
        args: Map<String, kotlinx.serialization.json.JsonElement>,
        ctx: GenerationContext,
        onConnected: suspend (Long) -> Unit = {},
    ): TimedToolOutcome {
        val command = arg(args, "command")
        if (command.isBlank()) return TimedToolOutcome(jsonError(ProcessTools.START_REMOTE, "no_command"))
        val waitMs = ProcessTools.parseOptionalWaitMs(arg(args, "wait_ms"))
            ?: return TimedToolOutcome(waitMsError(ProcessTools.START_REMOTE, arg(args, "server"), command))
        val serverName = arg(args, "server")
        val workdir = arg(args, "workdir")
        val device = resolveDevice(serverName, ctx)
            ?: return TimedToolOutcome(
                jsonError(ProcessTools.START_REMOTE, serverNotFound(serverName, ctx), server = serverName),
            )
        if (device.sshHost.isBlank()) {
            return TimedToolOutcome(
                jsonError(ProcessTools.START_REMOTE, "SSH device \"${device.name}\" has no host configured."),
            )
        }
        if (!confirm(device, "$ $command", false)) {
            return TimedToolOutcome(
                jsonError(
                    ProcessTools.START_REMOTE,
                    "denied_by_user: the user declined to run this command",
                    server = serverName,
                    command = command,
                ),
            )
        }
        val client = device.createSshClient(persistDetectedShell)
        var connectMs: Long? = null
        var handedOff = false
        return try {
            connectMs = client.ensureSession()
            onConnected(connectMs)
            val startedAt = SystemClock.elapsedRealtime()
            val session = client.startCommand(command, workdir)
            val id = AsyncCommandId.ssh(device.id)
            val run = RemoteAsyncRun(id, command, device.name.ifBlank { "Untitled" }, session)
            AsyncCommandRegistry.put(run, waiting = true, conversationId = ctx.conversationId)
            handedOff = true
            val text = try {
                settle(ProcessTools.START_REMOTE, run, waitMs, intSetArg(args, "ok_exit"))
            } finally {
                AsyncCommandRegistry.endWait(run.id.encode())
            }
            TimedToolOutcome(
                text = text,
                connectMs = connectMs,
                execMs = if (run.isFinished()) SystemClock.elapsedRealtime() - startedAt else null,
            )
        } catch (cancelled: CancellationException) {
            if (!handedOff) client.close()
            throw cancelled
        } catch (error: Exception) {
            if (!handedOff) client.close()
            TimedToolOutcome(
                text = jsonError(
                    ProcessTools.START_REMOTE,
                    error.message ?: "Failed to start remote command",
                    server = device.name,
                    command = command,
                ),
                connectMs = connectMs,
            )
        }
    }

    private fun peek(args: Map<String, kotlinx.serialization.json.JsonElement>): String {
        val rawId = processId(args)
        val run = resolveRun(rawId) ?: return unknownId(ProcessTools.GET, rawId)
        return report(ProcessTools.GET, run, intSetArg(args, "ok_exit"))
    }

    private fun listLive(): String {
        val items = AsyncCommandRegistry.liveSnapshot()
        return kotlinx.serialization.json.buildJsonObject {
            put("type", ProcessTools.LIST)
            put("ok", true)
            putJsonArray("processes") {
                items.forEach { item ->
                    add(
                        buildJsonObject {
                            put(ProcessTools.PROCESS_ID, item.id)
                            put("command", item.command)
                            put("server", item.server)
                            put("waiting", item.waiting)
                        },
                    )
                }
            }
        }.toString()
    }

    private suspend fun wait(args: Map<String, kotlinx.serialization.json.JsonElement>): String {
        val rawId = processId(args)
        val waitMs = ProcessTools.parseRequiredWaitMs(arg(args, "wait_ms"))
            ?: return waitMsError(ProcessTools.WAIT)
        val run = resolveRun(rawId) ?: return unknownId(ProcessTools.WAIT, rawId)
        AsyncCommandRegistry.beginWait(run.id.encode())
        try {
            return settle(ProcessTools.WAIT, run, waitMs, intSetArg(args, "ok_exit"))
        } finally {
            AsyncCommandRegistry.endWait(run.id.encode())
        }
    }

    private suspend fun stop(args: Map<String, kotlinx.serialization.json.JsonElement>): String {
        val rawId = processId(args)
        val run = resolveRun(rawId) ?: return unknownId(ProcessTools.STOP, rawId)
        AsyncCommandRegistry.beginWait(run.id.encode())
        try {
            run.stop()
        val deadline = System.currentTimeMillis() + ProcessTools.STOP_SETTLE_MS.toLong()
        while (!run.isFinished() && System.currentTimeMillis() < deadline) {
            delay(50)
        }
        val snapshot = run.snapshot()
        val wire = ProcessTools.stopWire(snapshot.running)
        return formatExecResult(
            type = ProcessTools.STOP,
            command = run.command,
            server = run.server,
            exitCode = snapshot.exitCode ?: -1,
            stdout = snapshot.stdout,
            stderr = snapshot.stderr,
            okExit = null,
            detailStore = detailStore,
            processId = run.id.encode(),
            running = wire.running,
            stopped = wire.stopped,
            ok = wire.ok,
            error = wire.error,
        )
        } finally {
            AsyncCommandRegistry.endWait(run.id.encode())
        }
    }

    private suspend fun settle(
        type: String,
        run: AsyncCommandRun,
        waitMs: Int,
        okExit: Set<Int>?,
    ): String {
        val deadline = System.currentTimeMillis() + waitMs.toLong()
        while (!run.isFinished() && System.currentTimeMillis() < deadline) {
            delay(50)
        }
        return report(type, run, okExit)
    }

    private fun report(type: String, run: AsyncCommandRun, okExit: Set<Int>?): String {
        val snapshot = run.snapshot()
        val id = run.id.encode()
        if (snapshot.running) {
            return formatAsyncRunning(type, run.command, run.server, id, snapshot.stdout, snapshot.stderr)
        }
        return formatExecResult(
            type = type,
            command = run.command,
            server = run.server,
            exitCode = snapshot.exitCode ?: -1,
            stdout = snapshot.stdout,
            stderr = snapshot.stderr,
            okExit = okExit,
            detailStore = detailStore,
            processId = id,
            running = false,
        )
    }

    private fun resolveRun(rawId: String): AsyncCommandRun? {
        if (rawId.isBlank() || AsyncCommandId.parse(rawId) == null) return null
        return AsyncCommandRegistry.get(rawId)
    }

    private fun processId(args: Map<String, kotlinx.serialization.json.JsonElement>): String =
        arg(args, ProcessTools.PROCESS_ID)

    private fun unknownId(type: String, rawId: String): String =
        jsonError(
            type,
            if (rawId.isBlank()) "${ProcessTools.PROCESS_ID} is required"
            else "Unknown ${ProcessTools.PROCESS_ID}. The process is gone if the app or SSH session died.",
        )

    private fun waitMsError(type: String, server: String? = null, command: String? = null): String =
        jsonError(type, "wait_ms must be an integer", server, command)
}

private class LocalAsyncRun(
    override val id: AsyncCommandId,
    override val command: String,
    private val process: Process,
) : AsyncCommandRun {
    override val server: String = "Local"
    private val stdout = ByteArrayOutputStream()
    private val stderr = ByteArrayOutputStream()
    private val exitListeners = mutableListOf<() -> Unit>()
    private val exitNotified = AtomicBoolean(false)

    init {
        Thread({ runCatching { process.inputStream.copyTo(stdout) } }, "agora-async-out").apply {
            isDaemon = true
            start()
        }
        Thread({ runCatching { process.errorStream.copyTo(stderr) } }, "agora-async-err").apply {
            isDaemon = true
            start()
        }
        Thread({
            runCatching { process.waitFor() }
            notifyExit()
        }, "agora-async-wait").apply {
            isDaemon = true
            start()
        }
    }

    override fun isFinished(): Boolean = !process.isAlive

    override fun snapshot(): AsyncCommandSnapshot {
        val out: ByteArray
        val err: ByteArray
        synchronized(stdout) { out = stdout.toByteArray() }
        synchronized(stderr) { err = stderr.toByteArray() }
        return AsyncCommandSnapshot(
            running = process.isAlive,
            exitCode = if (process.isAlive) null else process.exitValue(),
            stdout = String(out, StandardCharsets.UTF_8),
            stderr = String(err, StandardCharsets.UTF_8),
        )
    }

    override fun stop() {
        UnixProcessTree.kill(process)
    }

    override fun release() {
        stop()
    }

    override fun observeExit(onExit: () -> Unit) {
        if (isFinished()) {
            onExit()
            return
        }
        synchronized(exitListeners) { exitListeners += onExit }
    }

    private fun notifyExit() {
        if (!exitNotified.compareAndSet(false, true)) return
        val listeners = synchronized(exitListeners) { exitListeners.toList() }
        listeners.forEach { listener -> runCatching { listener() } }
    }
}

private class RemoteAsyncRun(
    override val id: AsyncCommandId,
    override val command: String,
    override val server: String,
    private val session: SshExecSession,
) : AsyncCommandRun {
    private val closed = AtomicBoolean(false)
    private val exitListeners = mutableListOf<() -> Unit>()
    private val exitNotified = AtomicBoolean(false)

    init {
        Thread({
            while (!session.isFinished()) {
                runCatching { Thread.sleep(100L) }
            }
            notifyExit()
        }, "agora-async-ssh-wait").apply {
            isDaemon = true
            start()
        }
    }

    override fun isFinished(): Boolean = session.isFinished()

    override fun snapshot(): AsyncCommandSnapshot {
        val result = session.snapshot()
        val finished = session.isFinished()
        if (finished) {
            closeSession()
            notifyExit()
        }
        return AsyncCommandSnapshot(
            running = !finished,
            exitCode = if (finished) result.exitCode else null,
            stdout = result.stdout,
            stderr = result.stderr,
        )
    }

    override fun stop() {
        session.stop()
    }

    override fun release() {
        closeSession()
    }

    override fun observeExit(onExit: () -> Unit) {
        if (isFinished()) {
            onExit()
            return
        }
        synchronized(exitListeners) { exitListeners += onExit }
    }

    private fun notifyExit() {
        if (!exitNotified.compareAndSet(false, true)) return
        val listeners = synchronized(exitListeners) { exitListeners.toList() }
        listeners.forEach { listener -> runCatching { listener() } }
    }

    private fun closeSession() {
        if (closed.compareAndSet(false, true)) {
            session.release()
        }
    }
}

private fun startLocalProcess(context: Context, command: String, workdir: String): Process {
    val sh = listOf("/system/bin/sh", "/system/bin/mksh", "/bin/sh").firstOrNull { File(it).canRead() }
        ?: "/system/bin/sh"
    val builder = ProcessBuilder(listOf(sh, "-c", command))
    workdir.takeIf { it.isNotBlank() }?.let { File(it).takeIf { dir -> dir.isDirectory } }?.let { builder.directory(it) }
    context.applicationInfo.nativeLibraryDir?.takeIf { it.isNotBlank() }?.let { libDir ->
        val env = builder.environment()
        val existing = env["LD_LIBRARY_PATH"]
        env["LD_LIBRARY_PATH"] = if (existing.isNullOrBlank()) libDir else "$libDir:$existing"
    }
    builder.redirectErrorStream(false)
    return builder.start()
}
