package com.newoether.agora.tool

import com.newoether.agora.util.PortableGlobMatcher
import com.newoether.agora.util.SftpRemotePath
import com.newoether.agora.util.SshClient
import com.newoether.agora.util.SshExecShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets

internal class SftpFileAccess(
    private val client: SshClient,
) : FileAccess {

    override suspend fun read(path: String, startLine: Int?, endLine: Int?): FileReadOutcome =
        withContext(Dispatchers.IO) {
            runCatching {
                val remote = SftpRemotePath.normalize(path)
                val stat = client.fileStat(remote)
                if (!stat.isFile) {
                    return@withContext FileReadOutcome(path, stat.size, 0, 1, 0, null, false, "Not a file: $path")
                }
                val ranged = startLine != null || endLine != null
                if (FileTextOps.omitUnreadBody(stat.size, ranged)) {
                    return@withContext FileReadOutcome(
                        path = remote,
                        size = stat.size,
                        lineCount = null,
                        startLine = 1,
                        endLine = 0,
                        content = readCapReason(stat.size, null),
                        metadataOnly = true,
                    )
                }
                if (ranged) {
                    val start = (startLine ?: 1).coerceAtLeast(1)
                    val text = client.fileReadLines(
                        remote,
                        start,
                        endLine,
                        FileTextOps.DEFAULT_MAX_READ_BYTES,
                    )
                    val sliceLines = if (text.isEmpty()) 0 else FileTextOps.countLines(text)
                    return@withContext FileReadOutcome(
                        path = remote,
                        size = stat.size,
                        lineCount = null,
                        startLine = start,
                        endLine = if (sliceLines == 0) start - 1 else start + sliceLines - 1,
                        content = text,
                        metadataOnly = false,
                    )
                }
                val text = client.fileRead(remote, 0, 0)
                if (text.contains('\u0000')) {
                    return@withContext FileReadOutcome(
                        remote, stat.size, 0, 1, 0, null, false,
                        "File looks binary. Use download_file / upload_file_to_server.",
                    )
                }
                finishRead(remote, stat.size, text, startLine, endLine)
            }.getOrElse {
                FileReadOutcome(path, 0, 0, 1, 0, null, false, it.message ?: "SFTP read failed")
            }
        }

    override suspend fun write(path: String, content: String): FileWriteOutcome =
        withContext(Dispatchers.IO) {
            val bytes = content.toByteArray(StandardCharsets.UTF_8)
            if (bytes.size > FileTextOps.MAX_WRITE_BYTES) {
                return@withContext FileWriteOutcome(path, 0, "content exceeds ${FileTextOps.MAX_WRITE_BYTES} bytes")
            }
            val remote = SftpRemotePath.normalize(path)
            val error = client.fileWriteAtomic(remote, content)
            if (error != null) FileWriteOutcome(path, 0, error) else FileWriteOutcome(remote, bytes.size)
        }

    override suspend fun replace(
        path: String,
        oldString: String,
        newString: String,
        replaceAll: Boolean,
    ): FileReplaceOutcome = withContext(Dispatchers.IO) {
        runCatching {
            val remote = SftpRemotePath.normalize(path)
            val stat = client.fileStat(remote)
            if (!stat.isFile) return@withContext FileReplaceOutcome(path, error = "File not found: $path")
            if (stat.size > FileTextOps.MAX_REPLACE_BYTES) {
                return@withContext FileReplaceOutcome(
                    path,
                    error = "File is larger than ${FileTextOps.MAX_REPLACE_BYTES} bytes; use write_remote_file or a smaller hunk",
                )
            }
            val text = client.fileRead(remote, 0, 0)
            when (val result = FileTextOps.applyReplace(text, oldString, newString, replaceAll)) {
                FileTextOps.ReplaceResult.EmptyOld ->
                    FileReplaceOutcome(remote, error = "old_string must not be empty")
                is FileTextOps.ReplaceResult.NotFound ->
                    FileReplaceOutcome(remote, lineNumbers = result.lineHints, error = notFoundMessage(result.lineHints))
                is FileTextOps.ReplaceResult.Ambiguous ->
                    FileReplaceOutcome(
                        remote,
                        occurrences = result.occurrences,
                        lineNumbers = result.lineNumbers,
                        error = ambiguousMessage(result.occurrences, result.lineNumbers),
                    )
                is FileTextOps.ReplaceResult.Applied -> {
                    val error = client.fileWriteAtomic(remote, result.text)
                    if (error != null) FileReplaceOutcome(remote, error = error)
                    else FileReplaceOutcome(remote, occurrences = result.occurrences)
                }
            }
        }.getOrElse {
            FileReplaceOutcome(path, error = it.message ?: "SFTP replace failed")
        }
    }

    override suspend fun grep(
        pattern: String,
        basePath: String,
        glob: String,
        noIgnore: Boolean,
        extraIgnore: List<String>,
        offset: Int,
    ): FileGrepOutcome = withContext(Dispatchers.IO) {
        val (kind, base) = execSearchRoot(basePath)
        val timeout = FileSearchPolicy.GREP_TIME_BUDGET_MS.toInt()
        val posix = if (RemoteFileSearch.preferPosixSearch(kind)) {
            runSearch(
                RemoteFileSearch.posixGrepCommand(pattern, base, glob, extraIgnore, noIgnore),
                timeout,
                posixNoMatchOk = true,
            )
        } else {
            null
        }
        if (posix?.timedOut == true) {
            return@withContext FileSearchPolicy.finishGrep(
                pattern = pattern,
                page = emptyList(),
                totalMatches = 0,
                offset = offset,
                scannedFiles = 0,
                skippedOversize = 0,
                skippedBinary = 0,
                ignoredDirs = emptyList(),
                scanReason = FileSearchPolicy.REASON_TIME,
                engine = FileSearchPolicy.ENGINE_GREP,
            )
        }
        if (posix != null && posix.ok) {
            val parsed = RemoteFileSearch.parsePowerShellGrep(posix.stdout)
            return@withContext finishRemoteGrep(
                pattern,
                parsed.matches,
                offset,
                FileSearchPolicy.ENGINE_GREP,
                timedOut = false,
                skippedOversize = parsed.skippedOversize,
            )
        }
        val ps = runSearch(
            RemoteFileSearch.powershellGrepCommand(pattern, base, glob, extraIgnore, noIgnore),
            timeout,
            posixNoMatchOk = false,
        )
        if (ps?.timedOut == true) {
            return@withContext FileSearchPolicy.finishGrep(
                pattern = pattern,
                page = emptyList(),
                totalMatches = 0,
                offset = offset,
                scannedFiles = 0,
                skippedOversize = 0,
                skippedBinary = 0,
                ignoredDirs = emptyList(),
                scanReason = FileSearchPolicy.REASON_TIME,
                engine = FileSearchPolicy.ENGINE_POWERSHELL,
            )
        }
        if (ps != null && ps.ok) {
            val parsed = RemoteFileSearch.parsePowerShellGrep(ps.stdout)
            return@withContext finishRemoteGrep(
                pattern,
                parsed.matches,
                offset,
                FileSearchPolicy.ENGINE_POWERSHELL,
                timedOut = false,
                skippedOversize = parsed.skippedOversize,
            )
        }
        FileSearchPolicy.finishGrep(
            pattern = pattern,
            page = emptyList(),
            totalMatches = 0,
            offset = offset,
            scannedFiles = 0,
            skippedOversize = 0,
            skippedBinary = 0,
            ignoredDirs = emptyList(),
            scanReason = null,
            error = RemoteFileSearch.grepFailure(posix?.error, ps?.error),
        )
    }

    override suspend fun glob(
        pattern: String,
        basePath: String,
        noIgnore: Boolean,
        extraIgnore: List<String>,
        offset: Int,
    ): FileGlobOutcome = withContext(Dispatchers.IO) {
        val (kind, base) = execSearchRoot(basePath)
        val timeout = FileSearchPolicy.GREP_TIME_BUDGET_MS.toInt()
        val posix = if (RemoteFileSearch.preferPosixSearch(kind)) {
            rejectWindowsFind(
                runSearch(
                    RemoteFileSearch.posixFindCommand(base, extraIgnore, noIgnore),
                    timeout,
                    posixNoMatchOk = true,
                ),
            )
        } else {
            null
        }
        if (posix?.timedOut == true) {
            return@withContext FileSearchPolicy.finishGlob(
                page = emptyList(),
                totalMatched = 0,
                offset = offset,
                ignoredDirs = emptyList(),
                scanReason = FileSearchPolicy.REASON_TIME,
                walked = 0,
            )
        }
        if (posix != null && posix.ok) {
            return@withContext finishRemoteGlob(pattern, posix.stdout, offset, timedOut = false) {
                RemoteFileSearch.parsePosixFind(it)
            }
        }
        val ps = runSearch(
            RemoteFileSearch.powershellGlobCommand(
                base, extraIgnore, noIgnore, RemoteFileSearch.globNameFilter(pattern),
            ),
            timeout,
            posixNoMatchOk = false,
        )
        if (ps?.timedOut == true) {
            return@withContext FileSearchPolicy.finishGlob(
                page = emptyList(),
                totalMatched = 0,
                offset = offset,
                ignoredDirs = emptyList(),
                scanReason = FileSearchPolicy.REASON_TIME,
                walked = 0,
            )
        }
        if (ps != null && ps.ok) {
            return@withContext finishRemoteGlob(pattern, ps.stdout, offset, timedOut = false) {
                RemoteFileSearch.parsePosixFind(it)
            }
        }
        FileSearchPolicy.finishGlob(
            page = emptyList(),
            totalMatched = 0,
            offset = offset,
            ignoredDirs = emptyList(),
            scanReason = null,
            error = RemoteFileSearch.globSearchFailure(posix?.error, ps?.error),
        )
    }

    override suspend fun list(
        basePath: String,
        offset: Int,
        dirsOnly: Boolean,
    ): FileListOutcome = withContext(Dispatchers.IO) {
        runCatching {
            val listed = client.fileListLevel(basePath)
            val entries = FileSearchPolicy.sortListEntries(
                listed.mapNotNull { row ->
                    if (dirsOnly && !row.isDir) return@mapNotNull null
                    FileListEntry(
                        path = row.path,
                        type = if (row.isDir) FileSearchPolicy.TYPE_DIR else FileSearchPolicy.TYPE_FILE,
                        size = if (row.isDir) null else row.size,
                        mtimeMs = row.mtimeSec.toLong() * 1000L,
                    )
                },
            )
            FileSearchPolicy.finishList(
                page = entries.drop(offset).take(FileTextOps.MAX_LIST_FILES),
                totalMatched = entries.size,
                offset = offset,
            )
        }.getOrElse {
            FileSearchPolicy.finishList(
                page = emptyList(),
                totalMatched = 0,
                offset = offset,
                error = it.message ?: "SFTP list failed",
            )
        }
    }

    private suspend fun execSearchRoot(basePath: String): Pair<SshExecShell, String> {
        val kind = client.resolvedShell().kind
        return kind to RemoteFileSearch.execPath(basePath, kind)
    }

    private data class SearchExec(
        val stdout: String,
        val stderr: String = "",
        val ok: Boolean,
        val timedOut: Boolean,
        val error: String?,
    )

    private fun rejectWindowsFind(exec: SearchExec?): SearchExec? {
        if (exec == null || exec.timedOut || !exec.ok) return exec
        if (!RemoteFileSearch.looksLikeWindowsFindExe(exec.stdout, exec.stderr)) return exec
        return exec.copy(ok = false, error = "windows find.exe is not GNU find")
    }

    private suspend fun runSearch(
        command: String,
        timeoutMs: Int,
        posixNoMatchOk: Boolean,
    ): SearchExec? = try {
        val result = client.executeCommand(command, execTimeoutMs = timeoutMs)
        val ok = RemoteFileSearch.searchSucceeded(result.exitCode, posixNoMatchOk)
        SearchExec(
            stdout = result.stdout,
            stderr = result.stderr,
            ok = ok,
            timedOut = false,
            error = if (ok) null else result.stderr.trim().ifBlank { "exit ${result.exitCode}" },
        )
    } catch (error: Exception) {
        val timedOut = error.message?.contains("timed out", ignoreCase = true) == true
        SearchExec(stdout = "", ok = false, timedOut = timedOut, error = error.message)
    }

    private fun finishRemoteGrep(
        pattern: String,
        matches: List<FileGrepMatch>,
        offset: Int,
        engine: String,
        timedOut: Boolean,
        skippedOversize: Int = 0,
    ): FileGrepOutcome {
        val page = matches.drop(offset).take(FileTextOps.MAX_GREP_MATCHES)
        val scanReason = if (timedOut) FileSearchPolicy.REASON_TIME else null
        return FileSearchPolicy.finishGrep(
            pattern = pattern,
            page = page,
            totalMatches = matches.size,
            offset = offset,
            scannedFiles = matches.map { it.path }.toSet().size,
            skippedOversize = skippedOversize,
            skippedBinary = 0,
            ignoredDirs = emptyList(),
            scanReason = scanReason,
            engine = engine,
        )
    }

    private fun finishRemoteGlob(
        pattern: String,
        stdout: String,
        offset: Int,
        timedOut: Boolean,
        parse: (String) -> List<String>,
    ): FileGlobOutcome {
        val matcher = FileSearchPolicy.globSearchPattern(pattern)
        val all = parse(stdout).filter { PortableGlobMatcher.matches(matcher, it.replace('\\', '/')) }
        val capped = all.take(FileSearchPolicy.WALK_FILE_CAP + 1)
        val walkReason = when {
            timedOut -> FileSearchPolicy.REASON_TIME
            capped.size > FileSearchPolicy.WALK_FILE_CAP -> FileSearchPolicy.REASON_WALK
            else -> null
        }
        val matched = capped.take(FileSearchPolicy.WALK_FILE_CAP)
        val page = matched.drop(offset).take(FileTextOps.MAX_LIST_FILES)
        return FileSearchPolicy.finishGlob(
            page = page,
            totalMatched = matched.size,
            offset = offset,
            ignoredDirs = emptyList(),
            scanReason = walkReason,
            walked = matched.size,
        )
    }
}
