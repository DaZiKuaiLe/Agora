package com.newoether.agora.tool

import android.content.Context
import com.newoether.agora.data.ShellDeviceConfig
import com.newoether.agora.util.Constants
import com.newoether.agora.util.PortableGlobMatcher
import com.newoether.agora.util.ShellClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Local on-device backend: system-shell command execution via [ProcessBuilder].
 *
 * Packaged CLI tools (`node`, `ffmpeg`, `ffprobe`, …) are resolved from nativeLibraryDir and
 * rewritten into the shell command by [LocalToolchain] so Android 10+ exec restrictions are
 * respected without a separate embedded JS engine.
 */
internal class NodeBackend(
    private val context: Context,
    override val device: ShellDeviceConfig? = null,
) : Backend {

    private val serverName: String get() = device?.name?.ifBlank { "Local" } ?: "Local"
    private val tools: Map<String, String> by lazy { LocalToolchain.resolve(context) }

    // ── Command execution (system shell) ──────────────────

    override suspend fun executeCommand(cmd: String, workdir: String, timeoutMs: Int): String {
        val rewritten = LocalToolchain.rewriteCommand(cmd, tools)
        val result = runCommandInternal(rewritten, workdir, timeoutMs)
        val output = (result.stdout + if (result.stderr.isNotBlank()) "\n${result.stderr}" else "").trimEnd()
        return buildJsonObject {
            put("type", "execute_local_command")
            put("server", serverName)
            put("command", cmd)
            if (rewritten != cmd) put("resolved_command", rewritten)
            put("exit_code", result.exitCode)
            put("output", output)
            if (result.stderr.isNotBlank()) put("stderr", result.stderr)
            if (result.stdout.isNotBlank()) put("stdout", result.stdout)
            if (result.exitCode != 0) {
                put("error", "execution_error")
                put("message", result.errorMessage ?: "Non-zero exit code ${result.exitCode}")
            }
        }.toString()
    }

    override fun executeCommandEvents(
        cmd: String,
        workdir: String,
        timeoutMs: Int,
    ): Flow<ToolExecutionEvent> = flow {
        emit(ToolExecutionEvent.Progress("Running local command"))
        emit(ToolExecutionEvent.Completed(executeCommand(cmd, workdir, timeoutMs)))
    }

    // ── File operations (app-private filesystem) ──────────

    override suspend fun fileRead(path: String, offset: Long, limit: Long): String {
        val f = resolveLocalPath(path)
        if (!f.isFile) throw IllegalStateException("File not found: $path")
        val size = f.length().toInt()
        val start = offset.coerceIn(0, size.toLong()).toInt()
        val end = if (limit > 0) minOf(start + limit, size.toLong()).toInt() else minOf(start + Constants.MAX_TOOL_RESULT_LENGTH, size)
        val len = (end - start).coerceAtLeast(0)
        val bytes = ByteArray(len)
        f.inputStream().use { it.skip(start.toLong()); it.read(bytes) }
        return String(bytes, Charsets.UTF_8)
    }

    override suspend fun fileWrite(path: String, content: String): String? = withContext(Dispatchers.IO) {
        try {
            val f = resolveLocalPath(path)
            f.parentFile?.mkdirs()
            f.writeText(content, Charsets.UTF_8)
            null
        } catch (e: Throwable) { "Local file write failed: ${e.message}" }
    }

    override suspend fun fileGlob(pattern: String, basePath: String, depth: Int?): Result<List<String>> =
        withContext(Dispatchers.IO) {
            val base = resolveLocalPath(if (basePath.isBlank()) "." else basePath)
            if (!base.isDirectory) return@withContext Result.success(emptyList())
            val remaining = if (depth == null || depth <= 0) -1 else depth
            val files = mutableListOf<String>()
            walkGlob(base, files, remaining)
            val normalizedPattern = if (pattern.contains('/')) pattern else "**/$pattern"
            Result.success(files.filter { PortableGlobMatcher.matches(normalizedPattern, it) })
        }

    override suspend fun fileGrep(pattern: String, basePath: String, fileGlobPattern: String): Result<List<ShellClient.GrepMatch>> =
        withContext(Dispatchers.IO) {
            try {
                val base = resolveLocalPath(if (basePath.isBlank()) "." else basePath)
                val regex = try { Regex(pattern) } catch (_: Throwable) { Regex(java.util.regex.Pattern.quote(pattern)) }
                val files = if (fileGlobPattern.isNotBlank()) {
                    globPaths(fileGlobPattern, basePath).map { resolveLocalPath(it) }
                } else {
                    val list = mutableListOf<File>()
                    if (base.isFile) list.add(base) else base.walkTopDown().filter { it.isFile }.forEach { list.add(it) }
                    list
                }
                val matches = mutableListOf<ShellClient.GrepMatch>()
                for (file in files) {
                    try {
                        if (!file.isFile || file.length() > 500_000L) continue
                        val text = file.readText(Charsets.UTF_8)
                        if (text.contains('\u0000')) continue
                        text.lines().forEachIndexed { i, line ->
                            if (regex.containsMatchIn(line)) matches.add(ShellClient.GrepMatch(file.absolutePath, i + 1, line.take(500)))
                        }
                    } catch (_: Throwable) {}
                }
                Result.success(matches)
            } catch (e: Throwable) { Result.failure(e) }
        }

    override fun close() {}

    // ── Helpers ───────────────────────────────────────────

    private fun resolveLocalPath(path: String): File {
        val file = File(path)
        return if (file.isAbsolute) file.canonicalFile else File(context.filesDir, path).canonicalFile
    }

    private fun walkGlob(dir: File, result: MutableList<String>, remaining: Int) {
        try {
            dir.listFiles()?.forEach { child ->
                if (child.isDirectory) {
                    if (remaining < 0 || remaining > 1) {
                        walkGlob(child, result, if (remaining < 0) -1 else remaining - 1)
                    }
                } else {
                    result.add(child.absolutePath)
                }
            }
        } catch (_: Throwable) {}
    }

    private fun globPaths(pattern: String, basePath: String): List<String> {
        val base = resolveLocalPath(if (basePath.isBlank()) "." else basePath)
        if (!base.isDirectory) return emptyList()
        val files = mutableListOf<String>()
        walkGlob(base, files, -1)
        val normalizedPattern = if (pattern.contains('/')) pattern else "**/$pattern"
        return files.filter { PortableGlobMatcher.matches(normalizedPattern, it) }
    }

    private data class LocalCommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val errorMessage: String?,
    )

    private suspend fun runCommandInternal(
        cmd: String,
        workdir: String,
        timeoutMs: Int,
    ): LocalCommandResult = withContext(Dispatchers.IO) {
        val sh = shellExecutable()
        val pb = ProcessBuilder(listOf(sh, "-c", cmd))
        val resolvedWdir = workdir.takeIf { it.isNotBlank() }?.let { File(it).takeIf { f -> f.isDirectory } }
        if (resolvedWdir != null) pb.directory(resolvedWdir)
        // Packaged CLI deps (e.g. libavcodec.so for ffmpeg) live beside the executables in
        // nativeLibraryDir. Prefer ELF RUNPATH=$ORIGIN on those binaries; also export
        // LD_LIBRARY_PATH for environments that still honor it.
        context.applicationInfo.nativeLibraryDir?.takeIf { it.isNotBlank() }?.let { libDir ->
            val env = pb.environment()
            val existing = env["LD_LIBRARY_PATH"]
            env["LD_LIBRARY_PATH"] =
                if (existing.isNullOrBlank()) libDir else "$libDir:$existing"
        }
        pb.redirectErrorStream(false)
        val process = try {
            pb.start()
        } catch (e: Throwable) {
            return@withContext LocalCommandResult(-1, "", e.message ?: "Failed to start shell", e.message)
        }
        try {
            coroutineScope {
                val out = async(Dispatchers.IO) { process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() } }
                val err = async(Dispatchers.IO) { process.errorStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() } }
                val exit = withTimeoutOrNull(timeoutMs.toLong().coerceAtLeast(1L)) {
                    var code: Int? = null
                    while (code == null) {
                        code = runCatching { process.exitValue() }.getOrNull()
                        if (code == null) Thread.sleep(25)
                    }
                    code
                }
                if (exit == null) {
                    UnixProcessTree.kill(process)
                    LocalCommandResult(-1, out.await(), err.await(), "Timed out after ${timeoutMs}ms")
                } else {
                    LocalCommandResult(exit, out.await(), err.await(), null)
                }
            }
        } finally {
            runCatching { process.destroy() }
        }
    }

    private fun shellExecutable(): String {
        val candidates = listOf("/system/bin/sh", "/system/bin/mksh", "/bin/sh")
        for (c in candidates) {
            if (File(c).canRead()) return c
        }
        return "/system/bin/sh"
    }
}
