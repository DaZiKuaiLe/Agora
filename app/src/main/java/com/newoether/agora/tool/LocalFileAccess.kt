package com.newoether.agora.tool

import android.content.Context
import com.newoether.agora.sandbox.SandboxSharedStorageAccess
import com.newoether.agora.util.FileWalkResult
import com.newoether.agora.util.LocalDownloadTarget
import com.newoether.agora.util.PortableGlobMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal class LocalFileLocator(private val context: Context?) {
    fun resolve(raw: String): Result<File> {
        if (raw.isBlank()) return Result.failure(IllegalArgumentException("path is required"))
        val requested = File(raw)
        val resolved = runCatching {
            if (requested.isAbsolute) requested.canonicalFile
            else File(context?.filesDir ?: File("."), raw).canonicalFile
        }.getOrElse {
            return Result.failure(IllegalArgumentException("Invalid path"))
        }
        if (LocalDownloadTarget.isLockout(resolved)) {
            return Result.failure(
                SecurityException("path is not accessible: ${resolved.path}"),
            )
        }
        return Result.success(resolved)
    }

    fun isSharedStorage(file: File): Boolean {
        val appFiles = context?.filesDir?.canonicalFile ?: return true
        return !file.canonicalPath.startsWith(appFiles.path)
    }

    fun permissionError(file: File): String? {
        if (context == null) return null
        if (!isSharedStorage(file)) return null
        if (SandboxSharedStorageAccess.isGranted(context)) return null
        if (file.canRead() && file.isFile) return null
        return "Cannot access shared storage. Call request_all_files_access or grant all-files access in Remote Terminals settings first."
    }
}

internal class LocalFileAccess(
    private val locator: LocalFileLocator,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val grepTimeBudgetMs: Long = FileSearchPolicy.GREP_TIME_BUDGET_MS,
) : FileAccess {

    override suspend fun read(path: String, startLine: Int?, endLine: Int?): FileReadOutcome =
        withContext(Dispatchers.IO) {
            val file = locator.resolve(path).getOrElse {
                return@withContext FileReadOutcome(path, 0, 0, 1, 0, null, false, it.message)
            }
            locator.permissionError(file)?.let {
                return@withContext FileReadOutcome(path, 0, 0, 1, 0, null, false, it)
            }
            if (!file.isFile) {
                return@withContext FileReadOutcome(path, 0, 0, 1, 0, null, false, "File not found: $path")
            }
            val size = file.length()
            val ranged = startLine != null || endLine != null
            if (FileTextOps.omitUnreadBody(size, ranged)) {
                return@withContext FileReadOutcome(
                    path = file.absolutePath,
                    size = size,
                    lineCount = null,
                    startLine = 1,
                    endLine = 0,
                    content = readCapReason(size, null),
                    metadataOnly = true,
                )
            }
            val text = runCatching { file.readText(StandardCharsets.UTF_8) }.getOrElse {
                return@withContext FileReadOutcome(
                    path = file.absolutePath,
                    size = size,
                    lineCount = 0,
                    startLine = 1,
                    endLine = 0,
                    content = null,
                    metadataOnly = false,
                    error = it.message ?: "Failed to read file",
                )
            }
            if (text.contains('\u0000')) {
                return@withContext FileReadOutcome(
                    file.absolutePath, size, 0, 1, 0, null, false,
                    "File looks binary. Use download_file / upload_file_to_server or a command.",
                )
            }
            finishRead(file.absolutePath, size, text, startLine, endLine)
        }

    override suspend fun write(path: String, content: String): FileWriteOutcome =
        withContext(Dispatchers.IO) {
            if (content.toByteArray(StandardCharsets.UTF_8).size > FileTextOps.MAX_WRITE_BYTES) {
                return@withContext FileWriteOutcome(path, 0, "content exceeds ${FileTextOps.MAX_WRITE_BYTES} bytes")
            }
            val file = locator.resolve(path).getOrElse {
                return@withContext FileWriteOutcome(path, 0, it.message)
            }
            locator.permissionError(file)?.let {
                return@withContext FileWriteOutcome(path, 0, it)
            }
            try {
                file.parentFile?.mkdirs()
                val bytes = content.toByteArray(StandardCharsets.UTF_8)
                atomicWrite(file, bytes)
                FileWriteOutcome(file.absolutePath, bytes.size)
            } catch (error: Exception) {
                FileWriteOutcome(path, 0, error.message ?: "Local file write failed")
            }
        }

    override suspend fun replace(
        path: String,
        oldString: String,
        newString: String,
        replaceAll: Boolean,
    ): FileReplaceOutcome = withContext(Dispatchers.IO) {
        val file = locator.resolve(path).getOrElse {
            return@withContext FileReplaceOutcome(path, error = it.message)
        }
        locator.permissionError(file)?.let {
            return@withContext FileReplaceOutcome(path, error = it)
        }
        if (!file.isFile) return@withContext FileReplaceOutcome(path, error = "File not found: $path")
        if (file.length() > FileTextOps.MAX_REPLACE_BYTES) {
            return@withContext FileReplaceOutcome(
                path,
                error = "File is larger than ${FileTextOps.MAX_REPLACE_BYTES} bytes; use write_local_file or a smaller hunk",
            )
        }
        val text = runCatching { file.readText(StandardCharsets.UTF_8) }.getOrElse {
            return@withContext FileReplaceOutcome(path, error = it.message ?: "Failed to read file")
        }
        when (val result = FileTextOps.applyReplace(text, oldString, newString, replaceAll)) {
            FileTextOps.ReplaceResult.EmptyOld ->
                FileReplaceOutcome(file.absolutePath, error = "old_string must not be empty")
            is FileTextOps.ReplaceResult.NotFound ->
                FileReplaceOutcome(
                    file.absolutePath,
                    lineNumbers = result.lineHints,
                    error = notFoundMessage(result.lineHints),
                )
            is FileTextOps.ReplaceResult.Ambiguous ->
                FileReplaceOutcome(
                    file.absolutePath,
                    occurrences = result.occurrences,
                    lineNumbers = result.lineNumbers,
                    error = ambiguousMessage(result.occurrences, result.lineNumbers),
                )
            is FileTextOps.ReplaceResult.Applied -> {
                atomicWrite(file, result.text.toByteArray(StandardCharsets.UTF_8))
                FileReplaceOutcome(file.absolutePath, occurrences = result.occurrences)
            }
        }
    }

    override suspend fun grep(
        pattern: String,
        basePath: String,
        glob: String,
        noIgnore: Boolean,
        extraIgnore: List<String>,
        offset: Int,
    ): FileGrepOutcome =
        withContext(Dispatchers.IO) {
            val regex = FileTextOps.compilePattern(pattern)
            val deadline = nowMs() + grepTimeBudgetMs
            val walk = collectPaths(
                basePath = basePath,
                glob = glob.ifBlank { "*" },
                noIgnore = noIgnore,
                extraIgnore = extraIgnore,
                includeDirs = false,
                deadlineMs = deadline,
            ).getOrElse {
                return@withContext FileSearchPolicy.finishGrep(
                    pattern = pattern,
                    page = emptyList(),
                    totalMatches = 0,
                    offset = offset,
                    scannedFiles = 0,
                    skippedOversize = 0,
                    skippedBinary = 0,
                    ignoredDirs = emptyList(),
                    scanReason = null,
                    error = it.message,
                )
            }
            val page = mutableListOf<FileGrepMatch>()
            var totalMatches = 0
            var scanned = 0
            var skippedOversize = 0
            var skippedBinary = 0
            var scanReason = walk.truncatedReason
            for (path in walk.files) {
                if (nowMs() >= deadline) {
                    scanReason = FileSearchPolicy.REASON_TIME
                    break
                }
                if (scanned >= FileTextOps.MAX_GREP_FILES) {
                    scanReason = FileSearchPolicy.REASON_FILE
                    break
                }
                val file = File(path)
                if (!file.isFile) continue
                if (file.length() > FileTextOps.MAX_GREP_FILE_BYTES) {
                    skippedOversize++
                    continue
                }
                scanned++
                val text = runCatching { file.readText(StandardCharsets.UTF_8) }.getOrNull() ?: continue
                if (text.contains('\u0000')) {
                    skippedBinary++
                    continue
                }
                text.lineSequence().forEachIndexed { index, line ->
                    if (regex.containsMatchIn(line)) {
                        val matchIndex = totalMatches
                        totalMatches++
                        if (matchIndex >= offset && page.size < FileTextOps.MAX_GREP_MATCHES) {
                            page.add(
                                FileGrepMatch(
                                    file.absolutePath,
                                    index + 1,
                                    line.take(FileTextOps.GREP_LINE_CHARS),
                                ),
                            )
                        }
                    }
                }
            }
            FileSearchPolicy.finishGrep(
                pattern = pattern,
                page = page,
                totalMatches = totalMatches,
                offset = offset,
                scannedFiles = scanned,
                skippedOversize = skippedOversize,
                skippedBinary = skippedBinary,
                ignoredDirs = walk.ignoredDirs,
                scanReason = scanReason,
                engine = FileSearchPolicy.ENGINE_JAVA,
            )
        }

    override suspend fun glob(
        pattern: String,
        basePath: String,
        noIgnore: Boolean,
        extraIgnore: List<String>,
        offset: Int,
    ): FileGlobOutcome =
        withContext(Dispatchers.IO) {
            val walk = collectPaths(
                basePath = basePath,
                glob = pattern.ifBlank { "*" },
                noIgnore = noIgnore,
                extraIgnore = extraIgnore,
                includeDirs = true,
                deadlineMs = nowMs() + grepTimeBudgetMs,
            ).getOrElse {
                return@withContext FileSearchPolicy.finishGlob(
                    page = emptyList(),
                    totalMatched = 0,
                    offset = offset,
                    ignoredDirs = emptyList(),
                    scanReason = null,
                    error = it.message,
                )
            }
            val page = walk.files.drop(offset).take(FileTextOps.MAX_LIST_FILES)
            FileSearchPolicy.finishGlob(
                page = page,
                totalMatched = walk.files.size,
                offset = offset,
                ignoredDirs = walk.ignoredDirs,
                scanReason = walk.truncatedReason,
                walked = walk.files.size,
            )
        }

    override suspend fun list(
        basePath: String,
        offset: Int,
        dirsOnly: Boolean,
    ): FileListOutcome =
        withContext(Dispatchers.IO) {
            val rawBase = basePath.ifBlank { "." }
            val base = locator.resolve(rawBase).getOrElse {
                return@withContext FileSearchPolicy.finishList(
                    page = emptyList(),
                    totalMatched = 0,
                    offset = offset,
                    error = it.message,
                )
            }
            locator.permissionError(base)?.let {
                return@withContext FileSearchPolicy.finishList(
                    page = emptyList(),
                    totalMatched = 0,
                    offset = offset,
                    error = it,
                )
            }
            if (base.isFile) {
                val entry = listEntry(base)
                val page = if (dirsOnly) emptyList() else listOf(entry).drop(offset).take(FileTextOps.MAX_LIST_FILES)
                return@withContext FileSearchPolicy.finishList(
                    page = page,
                    totalMatched = if (dirsOnly) 0 else 1,
                    offset = offset,
                )
            }
            if (!base.isDirectory) {
                return@withContext FileSearchPolicy.finishList(
                    page = emptyList(),
                    totalMatched = 0,
                    offset = offset,
                    error = "Directory not found: $rawBase",
                )
            }
            val children = base.listFiles()?.toList().orEmpty()
            val entries = FileSearchPolicy.sortListEntries(
                children.mapNotNull { child ->
                    if (dirsOnly && !child.isDirectory) null else listEntry(child)
                },
            )
            FileSearchPolicy.finishList(
                page = entries.drop(offset).take(FileTextOps.MAX_LIST_FILES),
                totalMatched = entries.size,
                offset = offset,
            )
        }

    private fun listEntry(file: File): FileListEntry {
        val isDir = file.isDirectory
        return FileListEntry(
            path = file.absolutePath,
            type = if (isDir) FileSearchPolicy.TYPE_DIR else FileSearchPolicy.TYPE_FILE,
            size = if (isDir) null else file.length(),
            mtimeMs = file.lastModified(),
        )
    }

    private fun collectPaths(
        basePath: String,
        glob: String,
        noIgnore: Boolean,
        extraIgnore: List<String>,
        includeDirs: Boolean,
        deadlineMs: Long?,
    ): Result<FileWalkResult> {
        val rawBase = basePath.ifBlank { "." }
        val base = locator.resolve(rawBase).getOrElse { return Result.failure(it) }
        locator.permissionError(base)?.let { return Result.failure(IllegalStateException(it)) }
        if (base.isFile) return Result.success(FileWalkResult(listOf(base.absolutePath)))
        if (!base.isDirectory) return Result.failure(IllegalArgumentException("Directory not found: $rawBase"))
        val collected = mutableListOf<String>()
        val ignored = linkedSetOf<String>()
        val walkReason = walkTree(
            dir = base,
            result = collected,
            noIgnore = noIgnore,
            extraIgnore = extraIgnore,
            includeDirs = includeDirs,
            ignored = ignored,
            deadlineMs = deadlineMs,
        )
        val pattern = FileSearchPolicy.globSearchPattern(glob)
        val matched = collected.filter { PortableGlobMatcher.matches(pattern, it.replace('\\', '/')) }
        return Result.success(
            FileWalkResult(
                files = matched,
                ignoredDirs = ignored.toList(),
                truncatedReason = walkReason,
            ),
        )
    }

    private fun walkTree(
        dir: File,
        result: MutableList<String>,
        noIgnore: Boolean,
        extraIgnore: List<String>,
        includeDirs: Boolean,
        ignored: MutableSet<String>,
        deadlineMs: Long?,
    ): String? {
        if (deadlineMs != null && nowMs() >= deadlineMs) return FileSearchPolicy.REASON_TIME
        if (result.size > FileSearchPolicy.WALK_FILE_CAP) return FileSearchPolicy.REASON_WALK
        try {
            dir.listFiles()?.forEach { child ->
                if (deadlineMs != null && nowMs() >= deadlineMs) return FileSearchPolicy.REASON_TIME
                if (result.size > FileSearchPolicy.WALK_FILE_CAP) return FileSearchPolicy.REASON_WALK
                if (child.isDirectory) {
                    if (FileSearchPolicy.shouldIgnoreDir(child.name, noIgnore, extraIgnore)) {
                        FileSearchPolicy.rememberIgnored(ignored, child.name)
                    } else {
                        if (includeDirs) result.add(child.absolutePath)
                        val nested = walkTree(
                            child,
                            result,
                            noIgnore,
                            extraIgnore,
                            includeDirs,
                            ignored,
                            deadlineMs,
                        )
                        if (nested != null) return nested
                    }
                } else {
                    result.add(child.absolutePath)
                }
            }
        } catch (_: Exception) {
        }
        return when {
            deadlineMs != null && nowMs() >= deadlineMs -> FileSearchPolicy.REASON_TIME
            result.size > FileSearchPolicy.WALK_FILE_CAP -> FileSearchPolicy.REASON_WALK
            else -> null
        }
    }

    private fun atomicWrite(file: File, bytes: ByteArray) {
        val tmp = File(file.parentFile, "${file.name}.agora-tmp")
        tmp.writeBytes(bytes)
        try {
            Files.move(
                tmp.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: Exception) {
            file.writeBytes(bytes)
            tmp.delete()
        }
    }
}

internal fun finishRead(
    path: String,
    size: Long,
    text: String,
    startLine: Int?,
    endLine: Int?,
): FileReadOutcome {
    val ranged = startLine != null || endLine != null
    val slice = FileTextOps.selectLines(text, startLine, endLine)
    val bytes = text.toByteArray(StandardCharsets.UTF_8).size
    if (!ranged && FileTextOps.exceedsReadCap(slice.totalLines, bytes)) {
        return FileReadOutcome(
            path = path,
            size = size,
            lineCount = slice.totalLines,
            startLine = 1,
            endLine = 0,
            content = readCapReason(size, slice.totalLines),
            metadataOnly = true,
        )
    }
    val sliceBytes = slice.text.toByteArray(StandardCharsets.UTF_8).size
    val content = if (sliceBytes > FileTextOps.DEFAULT_MAX_READ_BYTES) {
        String(slice.text.toByteArray(StandardCharsets.UTF_8), 0, FileTextOps.DEFAULT_MAX_READ_BYTES, StandardCharsets.UTF_8)
    } else {
        slice.text
    }
    return FileReadOutcome(
        path = path,
        size = size,
        lineCount = slice.totalLines,
        startLine = slice.startLine,
        endLine = slice.endLine,
        content = content,
        metadataOnly = false,
    )
}

internal fun readCapReason(size: Long, lineCount: Int?): String {
    val sizePart = if (lineCount != null) {
        "File is $size bytes / $lineCount lines."
    } else {
        "File is $size bytes."
    }
    return "$sizePart Pass start_line and end_line " +
        "(max ${FileTextOps.DEFAULT_MAX_READ_LINES} lines or ${FileTextOps.DEFAULT_MAX_READ_BYTES} bytes per read)."
}

internal fun notFoundMessage(hints: List<Int>): String {
    val rules = "Matching ignores CRLF vs LF. old_string is literal text, not a regex."
    return if (hints.isEmpty()) {
        "old_string not found. $rules"
    } else {
        "old_string not found as a contiguous hunk. $rules Nearby lines: ${hints.joinToString(", ")}"
    }
}

internal fun ambiguousMessage(count: Int, lines: List<Int>): String =
    "old_string matched $count times at lines ${lines.joinToString(", ")}. " +
        "Pass a unique hunk or replace_all=true."
