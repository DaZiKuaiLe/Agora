package com.newoether.agora.util

import com.jcraft.jsch.ChannelExec
import java.io.ByteArrayOutputStream

private const val STOP_SIGNAL_WAIT_MS = 400

/**
 * Open SSH exec channel that stays alive after the first wait budget.
 * The owning [SshClient] must not be closed until [release].
 */
class SshExecSession internal constructor(
    private val client: SshClient,
    private val channel: ChannelExec,
    private val stdoutStream: ByteArrayOutputStream,
    private val stderrStream: ByteArrayOutputStream,
    private val kind: SshExecShell,
) {
    fun isFinished(): Boolean = channel.isClosed

    fun snapshot(): SshClient.CommandResult {
        val stdout: ByteArray
        val stderr: ByteArray
        synchronized(stdoutStream) { stdout = stdoutStream.toByteArray() }
        synchronized(stderrStream) { stderr = stderrStream.toByteArray() }
        val exit = if (channel.isClosed) channel.exitStatus else -1
        return SshClient.CommandResult(
            stdout = SshRemoteShell.decodeOutput(stdout, kind),
            stderr = SshRemoteShell.decodeOutput(stderr, kind),
            exitCode = exit,
        )
    }

    fun stop() {
        if (channel.isClosed) return
        runCatching { channel.sendSignal("TERM") }
        waitUntilClosed(STOP_SIGNAL_WAIT_MS)
        if (channel.isClosed) return
        runCatching { channel.sendSignal("KILL") }
        waitUntilClosed(STOP_SIGNAL_WAIT_MS)
    }

    private fun waitUntilClosed(budgetMs: Int) {
        val deadline = System.currentTimeMillis() + budgetMs.toLong()
        while (!channel.isClosed && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    fun release() {
        runCatching { channel.disconnect() }
        client.close()
    }
}
