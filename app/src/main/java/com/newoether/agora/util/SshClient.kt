package com.newoether.agora.util

import android.os.SystemClock
import android.util.Base64
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * SSH client using JSch for shell command execution (exec channel)
 * and file operations (SFTP channel). All file operations are SFTP-based,
 * no remote command assumptions (compatible with Windows/Termux/BusyBox).
 *
 * User authentication is exclusive: a non-blank [SshAuth.privateKey] uses
 * publickey only; otherwise password auth is used.
 *
 * Host-key handling (trust-on-first-use):
 *  - [pinnedHostKey] set  → the server key MUST match, else the connection is
 *    rejected (defends against MITM / key changes).
 *  - [pinnedHostKey] blank + [allowUnknownHostKey] true → connect and capture the
 *    key (used by "Verify & pin" in settings, and as back-compat for un-pinned
 *    devices); the captured value is exposed via [capturedHostKey].
 *  - blank + false → unknown keys are rejected (fail-closed).
 *
 * NOT thread-safe — one borrower at a time. Short-lived tools go through
 * [SshSessionPool]; start_remote_process keeps a dedicated instance.
 */
class SshClient(
    private val host: String,
    private val port: Int,
    private val user: String,
    private val auth: SshAuth,
    private val timeoutMs: Int = Constants.NETWORK_CONNECT_TIMEOUT_MS.toInt(),
    private val pinnedHostKey: String = "",
    private val allowUnknownHostKey: Boolean = false,
    private val shellPreference: String = "auto",
    private val persistedDetectedShell: String = "",
    private val onShellDetected: ((String) -> Unit)? = null,
) {
    private var session: Session? = null

    /** The server host key (base64 of the public-key blob) seen on the last connect. */
    var capturedHostKey: String? = null
        private set

    // ── Connection ─────────────────────────────────────────

    /**
     * Ensures a TCP/SSH session exists. Returns handshake milliseconds, or 0 if this
     * instance already had a live session.
     */
    suspend fun ensureSession(): Long {
        session?.let { if (it.isConnected) return 0L }
        val startedAt = SystemClock.elapsedRealtime()
        getSession()
        return SystemClock.elapsedRealtime() - startedAt
    }

    private suspend fun getSession(): Session {
        session?.let { if (it.isConnected) return it }
        return withContext(Dispatchers.IO) {
            SshJschConfig.apply()
            val jsch = JSch()
            if (auth.usesPublicKey) {
                val keyBytes = auth.privateKey.toByteArray(Charsets.UTF_8)
                val passBytes = auth.passphrase.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8)
                jsch.addIdentity("agora", keyBytes, null, passBytes)
            }
            val s = jsch.getSession(user, host, port).apply {
                if (!auth.usesPublicKey) setPassword(auth.password)
                setConfig("PreferredAuthentications", auth.preferredAuthentications())
                hostKeyRepository = TofuHostKeyRepository()
                // "yes" makes JSch reject NOT_INCLUDED/CHANGED keys; "no" accepts (capture mode).
                setConfig("StrictHostKeyChecking", if (allowUnknownHostKey && pinnedHostKey.isBlank()) "no" else "yes")
                connect(timeoutMs)
                setServerAliveInterval(SERVER_ALIVE_INTERVAL_MS)
                setServerAliveCountMax(SERVER_ALIVE_COUNT_MAX)
            }
            session = s
            s
        }
    }

    /** TOFU host-key verifier: captures the presented key and accepts only a match. */
    private inner class TofuHostKeyRepository : HostKeyRepository {
        override fun check(host: String?, key: ByteArray): Int {
            val incoming = Base64.encodeToString(key, Base64.NO_WRAP)
            capturedHostKey = incoming
            return when {
                pinnedHostKey.isNotBlank() && pinnedHostKey == incoming -> HostKeyRepository.OK
                pinnedHostKey.isNotBlank() -> HostKeyRepository.CHANGED   // mismatch → reject
                else -> HostKeyRepository.NOT_INCLUDED                    // unknown
            }
        }
        override fun add(hostkey: HostKey?, ui: UserInfo?) {}
        override fun remove(host: String?, type: String?) {}
        override fun remove(host: String?, type: String?, key: ByteArray?) {}
        override fun getKnownHostsRepositoryID(): String = ""
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }

    companion object {
        private const val SERVER_ALIVE_INTERVAL_MS = 30_000
        private const val SERVER_ALIVE_COUNT_MAX = 3

        /** OpenSSH-style "SHA256:…" fingerprint of a base64 host-key blob, for display. */
        fun fingerprintSha256(base64Key: String): String = try {
            val bytes = Base64.decode(base64Key, Base64.DEFAULT)
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            "SHA256:" + Base64.encodeToString(digest, Base64.NO_WRAP or Base64.NO_PADDING)
        } catch (_: Exception) { "" }
    }

    fun isConnected(): Boolean = session?.isConnected == true

    fun close() {
        session?.disconnect()
        session = null
    }

    // ── Shell Command ──────────────────────────────────────

    data class CommandResult(
        val stdout: String,
        val stderr: String,
        val exitCode: Int
    )

    suspend fun executeCommand(
        command: String,
        workdir: String = "",
        execTimeoutMs: Int = timeoutMs
    ): CommandResult = withContext(Dispatchers.IO) {
        getSession()
        val resolved = resolveShell()
        val wrapped = SshRemoteShell.wrap(command, workdir, resolved)
        runExec(wrapped, execTimeoutMs, resolved.kind)
    }

    /** Login/exec shell after preference + probe. Used to pick find vs Get-ChildItem. */
    internal suspend fun resolvedShell(): ResolvedSshShell = resolveShell()

    /**
     * Opens an exec channel and returns immediately. Caller owns the session until
     * [SshExecSession.release]; do not call [close] from another path.
     */
    suspend fun startCommand(command: String, workdir: String = ""): SshExecSession {
        getSession()
        val resolved = resolveShell()
        val wrapped = SshRemoteShell.wrap(command, workdir, resolved)
        return withContext(Dispatchers.IO) {
            val sess = session ?: throw IllegalStateException("SSH session is not connected")
            val channel = sess.openChannel("exec") as ChannelExec
            channel.setCommand(wrapped)
            val stdoutStream = ByteArrayOutputStream()
            val stderrStream = ByteArrayOutputStream()
            channel.setOutputStream(stdoutStream)
            channel.setErrStream(stderrStream)
            channel.connect(timeoutMs)
            SshExecSession(this@SshClient, channel, stdoutStream, stderrStream, resolved.kind)
        }
    }

    private suspend fun resolveShell(): ResolvedSshShell {
        val key = SshRemoteShell.hostKey(user, host, port)
        SshRemoteShell.resolve(shellPreference, persistedDetectedShell, key)?.let { return it }
        val detected = SshRemoteShell.probe { cmd ->
            runCatching { runExec(cmd, timeoutMs, SshExecShell.POSIX).stdout }.getOrDefault("")
        }
        SshRemoteShell.remember(key, detected)
        onShellDetected?.invoke(SshRemoteShell.storedKind(detected))
        return ResolvedSshShell(detected, wrapCmdInPowershell = detected == SshExecShell.CMD)
    }

    private fun runExec(
        cmd: String,
        execTimeoutMs: Int,
        kind: SshExecShell,
    ): CommandResult {
        val sess = session ?: throw IllegalStateException("SSH session is not connected")
        val channel = sess.openChannel("exec") as ChannelExec
        channel.setCommand(cmd)
        val stdoutStream = ByteArrayOutputStream()
        val stderrStream = ByteArrayOutputStream()
        channel.setOutputStream(stdoutStream)
        channel.setErrStream(stderrStream)
        channel.connect(timeoutMs)
        val deadline = System.currentTimeMillis() + execTimeoutMs.coerceAtLeast(1_000)
        var timedOut = false
        while (!channel.isClosed) {
            if (System.currentTimeMillis() >= deadline) {
                timedOut = true
                break
            }
            try { Thread.sleep(100) } catch (_: InterruptedException) { break }
        }
        val exitCode = if (channel.isClosed) channel.exitStatus else -1
        channel.disconnect()
        if (timedOut) {
            throw IllegalStateException("Command timed out after ${execTimeoutMs}ms")
        }
        return CommandResult(
            stdout = SshRemoteShell.decodeOutput(stdoutStream.toByteArray(), kind),
            stderr = SshRemoteShell.decodeOutput(stderrStream.toByteArray(), kind),
            exitCode = exitCode
        )
    }

    // ── SFTP Helpers ───────────────────────────────────────

    private suspend fun <T> withSftp(block: suspend (ChannelSftp) -> T): T {
        val sess = getSession()
        return withContext(Dispatchers.IO) {
            val channel = sess.openChannel("sftp") as ChannelSftp
            channel.connect(timeoutMs)
            try {
                block(channel)
            } finally {
                channel.disconnect()
            }
        }
    }

    private fun ensureParentDirs(sftp: ChannelSftp, path: String) {
        val normalized = SftpRemotePath.normalize(path)
        val parts = normalized.split('/').filter { it.isNotBlank() }
        var current = if (normalized.startsWith('/')) "" else "."
        for (part in parts) {
            current += "/$part"
            if (current.isBlank() || current == "." || SftpRemotePath.isDriveRoot(current)) continue
            try { sftp.stat(current) } catch (_: Exception) {
                try { sftp.mkdir(current) } catch (_: Exception) { /* best-effort */ }
            }
        }
    }

    data class RemoteFileStat(
        val size: Long,
        val isFile: Boolean,
        val isDir: Boolean,
    )

    suspend fun fileStat(path: String): RemoteFileStat = withSftp { sftp ->
        val attrs = sftp.stat(SftpRemotePath.normalize(path))
        RemoteFileStat(size = attrs.size, isFile = attrs.isReg, isDir = attrs.isDir)
    }

    data class RemoteDirEntry(
        val path: String,
        val isDir: Boolean,
        val size: Long,
        val mtimeSec: Int,
    )

    /** One-level directory listing (ls). Does not recurse or download file bodies. */
    suspend fun fileListLevel(basePath: String): List<RemoteDirEntry> = withSftp { sftp ->
        val base = SftpRemotePath.normalize(
            basePath.ifBlank {
                try { sftp.pwd() } catch (_: Exception) { "/" }
            },
        ).trimEnd('/')
        val attrs = sftp.stat(base)
        if (!attrs.isDir) {
            return@withSftp listOf(
                RemoteDirEntry(path = base, isDir = false, size = attrs.size, mtimeSec = attrs.mTime),
            )
        }
        @Suppress("UNCHECKED_CAST")
        val listing = sftp.ls(base) as? List<ChannelSftp.LsEntry> ?: emptyList()
        listing.mapNotNull { entry ->
            val name = entry.filename
            if (name == "." || name == "..") return@mapNotNull null
            RemoteDirEntry(
                path = "$base/$name",
                isDir = entry.attrs.isDir,
                size = entry.attrs.size,
                mtimeSec = entry.attrs.mTime,
            )
        }
    }

    /**
     * Stream lines without loading the whole file. [visitor] returning false stops early.
     * Files larger than [maxBytes] are skipped (visitor is not called).
     */
    suspend fun fileForEachLine(
        path: String,
        maxBytes: Long,
        visitor: (lineNumber: Int, line: String) -> Boolean,
    ) = withSftp { sftp ->
        val remote = SftpRemotePath.normalize(path)
        val size = try {
            sftp.stat(remote).size
        } catch (_: Exception) {
            return@withSftp
        }
        if (size > maxBytes) return@withSftp
        sftp.get(remote).bufferedReader(Charsets.UTF_8).use { reader ->
            var number = 0
            while (true) {
                val line = reader.readLine() ?: break
                number++
                if (line.contains('\u0000')) break
                if (!visitor(number, line)) break
            }
        }
    }

    /**
     * Line-oriented read. When [startLine]/[endLine] are set, unread lines are skipped
     * instead of loading the whole file into memory.
     */
    suspend fun fileReadLines(
        path: String,
        startLine: Int,
        endLine: Int?,
        maxBytes: Int,
    ): String = withSftp { sftp ->
        val remote = SftpRemotePath.normalize(path)
        val builder = StringBuilder()
        var bytes = 0
        sftp.get(remote).bufferedReader(Charsets.UTF_8).use { reader ->
            var number = 0
            while (true) {
                val line = reader.readLine() ?: break
                number++
                if (number < startLine) continue
                if (endLine != null && number > endLine) break
                val addition = if (builder.isEmpty()) line else "\n$line"
                bytes += addition.toByteArray(Charsets.UTF_8).size
                if (bytes > maxBytes) break
                builder.append(addition)
            }
        }
        builder.toString()
    }

    // ── file_read ──────────────────────────────────────────

    suspend fun fileRead(
        path: String,
        offset: Long = 0,
        limit: Long = 0
    ): String = withSftp { sftp ->
        try {
            val inputStream = sftp.get(SftpRemotePath.normalize(path))
            val bytes = inputStream.readBytes()
            inputStream.close()
            val start = offset.coerceIn(0, bytes.size.toLong()).toInt()
            val end = if (limit > 0) {
                minOf(start + limit, bytes.size.toLong()).toInt()
            } else {
                bytes.size
            }
            String(bytes, start, end - start, Charsets.UTF_8)
        } catch (e: Exception) {
            throw IllegalStateException("SFTP read failed: ${e.message}")
        }
    }

    /**
     * Write via a sibling tmp name then rename. Returns null on success.
     */
    suspend fun fileWriteAtomic(path: String, content: String): String? = withSftp { sftp ->
        try {
            val remote = SftpRemotePath.normalize(path)
            val parent = SftpRemotePath.parent(remote)
            if (parent.isNotBlank()) {
                ensureParentDirs(sftp, parent)
            }
            val tmp = "$remote.agora-tmp"
            val bytes = content.toByteArray(Charsets.UTF_8)
            sftp.put(bytes.inputStream(), tmp, ChannelSftp.OVERWRITE)
            try {
                sftp.rm(remote)
            } catch (_: Exception) {
            }
            sftp.rename(tmp, remote)
            SftpRemotePath.sizeMismatch(bytes.size.toLong(), sftp.stat(remote).size, remote)
                ?: null
        } catch (e: Exception) {
            "SFTP write failed: ${e.message}"
        }
    }

    // ── file_write ─────────────────────────────────────────

    /**
     * Returns null on success, error message string on failure.
     */
    suspend fun fileWrite(path: String, content: String): String? = withSftp { sftp ->
        try {
            val remote = SftpRemotePath.normalize(path)
            val parent = SftpRemotePath.parent(remote)
            if (parent.isNotBlank()) {
                ensureParentDirs(sftp, parent)
            }
            val bytes = content.toByteArray(Charsets.UTF_8)
            sftp.put(bytes.inputStream(), remote, ChannelSftp.OVERWRITE)
            SftpRemotePath.sizeMismatch(bytes.size.toLong(), sftp.stat(remote).size, remote)
                ?: null
        } catch (e: Exception) {
            "SFTP write failed: ${e.message}"
        }
    }

    // ── file upload / download (binary, no base64) ─────────

    /**
     * Upload a local file to the remote path over SFTP as raw bytes.
     * Returns null on success, error message string on failure.
     */
    suspend fun uploadFile(localPath: String, remotePath: String): String? = withSftp { sftp ->
        try {
            val local = File(localPath)
            if (!local.isFile) return@withSftp "SFTP upload failed: local file not found"
            val remote = SftpRemotePath.normalize(remotePath)
            val parent = SftpRemotePath.parent(remote)
            if (parent.isNotBlank()) {
                ensureParentDirs(sftp, parent)
            }
            local.inputStream().use { input ->
                sftp.put(input, remote, ChannelSftp.OVERWRITE)
            }
            SftpRemotePath.sizeMismatch(local.length(), sftp.stat(remote).size, remote)
                ?: null
        } catch (e: Exception) {
            "SFTP upload failed: ${e.message}"
        }
    }

    /**
     * Download a remote file into [localDir] as raw bytes, auto-incrementing the name on
     * collision (a.jpg → a(1).jpg). Returns the absolute local path on success, error string on
     * failure.
     */
    suspend fun downloadFile(remotePath: String, localDir: String): String? = withSftp { sftp ->
        try {
            val remote = SftpRemotePath.normalize(remotePath)
            File(localDir).mkdirs()
            val destination = incrementName(File(localDir, SftpRemotePath.fileName(remote)))
            val expected = sftp.stat(remote).size
            sftp.get(remote, destination.absolutePath)
            SftpRemotePath.sizeMismatch(expected, destination.length(), remote)?.let { return@withSftp it }
            destination.absolutePath
        } catch (e: Exception) {
            "SFTP download failed: ${e.message}"
        }
    }

    private fun incrementName(file: File): File {
        if (!file.exists()) return file
        val base = file.nameWithoutExtension
        val ext = file.extension
        var index = 1
        while (true) {
            val candidate = File(file.parentFile, "${base}($index).${ext.ifBlank { "" }}")
            if (!candidate.exists()) return candidate
            index++
        }
    }

    // ── file_glob ──────────────────────────────────────────

    suspend fun fileGlob(
        pattern: String,
        basePath: String = "",
        depth: Int? = null,
        noIgnore: Boolean = false,
        deadlineMs: Long? = null,
    ): List<String> = fileWalk(pattern, basePath, depth, noIgnore, deadlineMs).files

    internal suspend fun fileWalk(
        pattern: String,
        basePath: String = "",
        depth: Int? = null,
        noIgnore: Boolean = false,
        deadlineMs: Long? = null,
    ): FileWalkResult =
        withSftp { sftp ->
            val base = SftpRemotePath.normalize(
                basePath.ifBlank {
                    try { sftp.pwd() } catch (_: Exception) { "/" }
                }
            ).trimEnd('/')
            val attrs = try {
                sftp.stat(base)
            } catch (_: Exception) {
                return@withSftp FileWalkResult(emptyList())
            }
            FileSearchRoots.of(base, isDirectory = attrs.isDir)?.let {
                return@withSftp FileWalkResult(it)
            }
            val allFiles = mutableListOf<String>()
            val ignored = linkedSetOf<String>()
            val remaining = if (depth == null || depth <= 0) -1 else depth
            val walkReason = sftpListRecursive(
                sftp = sftp,
                dir = base,
                result = allFiles,
                remaining = remaining,
                noIgnore = noIgnore,
                ignored = ignored,
                deadlineMs = deadlineMs,
            )
            FileWalkResult(
                files = globMatch(allFiles, base, pattern),
                ignoredDirs = ignored.toList(),
                truncatedReason = walkReason,
            )
        }

    // remaining: levels still allowed including the current dir's files. -1 = unlimited;
    // 1 = only this dir's files (no descent); >1 = descend with one fewer level.
    private fun sftpListRecursive(
        sftp: ChannelSftp,
        dir: String,
        result: MutableList<String>,
        remaining: Int,
        noIgnore: Boolean,
        ignored: MutableSet<String>,
        deadlineMs: Long?,
    ): String? {
        if (deadlineMs != null && System.currentTimeMillis() >= deadlineMs) {
            return FileSearchIgnore.REASON_TIME
        }
        if (result.size > FileSearchIgnore.WALK_FILE_CAP) return FileSearchIgnore.REASON_WALK
        try {
            @Suppress("UNCHECKED_CAST")
            val entries = sftp.ls(dir) as? List<ChannelSftp.LsEntry> ?: return null
            for (entry in entries) {
                if (deadlineMs != null && System.currentTimeMillis() >= deadlineMs) {
                    return FileSearchIgnore.REASON_TIME
                }
                if (result.size > FileSearchIgnore.WALK_FILE_CAP) return FileSearchIgnore.REASON_WALK
                val name = entry.filename
                if (name == "." || name == "..") continue
                val fullPath = "$dir/$name"
                if (entry.attrs.isDir) {
                    if (FileSearchIgnore.shouldIgnoreDir(name, noIgnore)) {
                        FileSearchIgnore.rememberIgnored(ignored, name)
                    } else if (remaining < 0 || remaining > 1) {
                        val nested = sftpListRecursive(
                            sftp = sftp,
                            dir = fullPath,
                            result = result,
                            remaining = if (remaining < 0) -1 else remaining - 1,
                            noIgnore = noIgnore,
                            ignored = ignored,
                            deadlineMs = deadlineMs,
                        )
                        if (nested != null) return nested
                    }
                } else {
                    result.add(fullPath)
                }
            }
        } catch (_: Exception) {
            // Permission denied or directory doesn't exist — skip
        }
        return if (result.size > FileSearchIgnore.WALK_FILE_CAP) FileSearchIgnore.REASON_WALK else null
    }

    private fun globMatch(files: List<String>, basePath: String, pattern: String): List<String> {
        val adjustedPattern = if (pattern.contains('/')) pattern else "**/$pattern"
        val fullPattern = "$basePath/$adjustedPattern"
        return files.filter { file -> PortableGlobMatcher.matches(fullPattern, file) }
    }

    // ── file_grep ──────────────────────────────────────────

    data class GrepMatch(
        val path: String,
        val line: Int,
        val content: String
    )

    /**
     * grep for pattern in files.
     * Strategy: try server-side grep first (fast), fall back to SFTP read+grep locally.
     */
    suspend fun fileGrep(
        pattern: String,
        basePath: String = "",
        fileGlob: String = ""
    ): Result<List<GrepMatch>> {
        val base = SftpRemotePath.normalize(basePath.ifBlank { "." })

        // Try server-side grep via exec channel first
        return try {
            val grepCmd = buildString {
                // -I skips binary files (matches the local-fallback NUL heuristic below).
                append("grep -rnI ")
                // The glob is model-supplied input on a non-confirmed (read-only) tool path, so it
                // must be shell-escaped exactly like pattern/base — otherwise it is a command
                // injection vector that bypasses the remote-mutation confirmation gate.
                if (fileGlob.isNotBlank()) append("--include=${escapeBash(fileGlob)} ")
                append("-- ")
                append(escapeBash(pattern))
                append(" ")
                append(escapeBash(base))
            }
            val result = executeCommand(grepCmd)
            when {
                result.exitCode == 0 || result.exitCode == 1 -> {
                    // 0=matches found, 1=no matches (both valid)
                    val matches = result.stdout.lines()
                        .filter { it.isNotBlank() }
                        .mapNotNull { parseGrepLine(it) }
                    Result.success(matches)
                }
                else -> {
                    // exitCode >= 2: grep error (not installed, bad args, etc.) — fallback
                    fallbackGrep(pattern, base, fileGlob)
                }
            }
        } catch (_: Exception) {
            fallbackGrep(pattern, base, fileGlob)
        }
    }

    private suspend fun fallbackGrep(
        regex: String,
        basePath: String,
        fileGlob: String
    ): Result<List<GrepMatch>> {
        return try {
            val globPattern = fileGlob.ifBlank { "*" }
            val files = fileGlob(globPattern, basePath)
            val pattern = try {
                Regex(regex)
            } catch (e: Exception) {
                Regex(java.util.regex.Pattern.quote(regex))
            }
            val allMatches = mutableListOf<GrepMatch>()
            val maxReadSize = 500_000L // MAX_FILE_CONTENT_READ_LENGTH equivalent
            for (file in files) {
                try {
                    val content = fileRead(file, 0, maxReadSize)
                    // Skip binary files (NUL-byte heuristic), as grep -I would.
                    if (content.contains('\u0000')) continue
                    content.lines().forEachIndexed { index, line ->
                        if (pattern.containsMatchIn(line)) {
                            allMatches.add(GrepMatch(
                                path = file,
                                line = index + 1,
                                content = line.take(500)
                            ))
                        }
                    }
                } catch (_: Exception) {
                    // Skip unreadable files
                }
            }
            Result.success(allMatches)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseGrepLine(line: String): GrepMatch? {
        // Format: "path:lineNum:content" or "path:lineNum:col:content"
        val firstColon = line.indexOf(':')
        if (firstColon < 0) return null
        val path = line.substring(0, firstColon)
        val afterPath = line.substring(firstColon + 1)
        val secondColon = afterPath.indexOf(':')
        if (secondColon < 0) return null
        val lineNumStr = afterPath.substring(0, secondColon)
        val content = afterPath.substring(secondColon + 1).take(500)
        val lineNum = lineNumStr.toIntOrNull() ?: return null
        return GrepMatch(path = path, line = lineNum, content = content)
    }

    private fun escapeBash(s: String): String = "'${s.replace("'", "'\\''")}'"
}
