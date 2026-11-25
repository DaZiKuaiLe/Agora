package com.newoether.agora.tool

/**
 * Shared file operations for local java.nio and remote SSH/SFTP. Tool names stay split;
 * this layer is the reusable implementation (later UI pickers can call it too).
 *
 * list is one directory (ls). glob/grep recurse on the venue that holds the files.
 */
internal interface FileAccess {
    suspend fun read(path: String, startLine: Int?, endLine: Int?): FileReadOutcome
    suspend fun write(path: String, content: String): FileWriteOutcome
    suspend fun replace(
        path: String,
        oldString: String,
        newString: String,
        replaceAll: Boolean,
    ): FileReplaceOutcome
    suspend fun grep(
        pattern: String,
        basePath: String,
        glob: String,
        noIgnore: Boolean = false,
        extraIgnore: List<String> = emptyList(),
        offset: Int = 0,
    ): FileGrepOutcome
    suspend fun glob(
        pattern: String,
        basePath: String,
        noIgnore: Boolean = false,
        extraIgnore: List<String> = emptyList(),
        offset: Int = 0,
    ): FileGlobOutcome
    suspend fun list(
        basePath: String,
        offset: Int = 0,
        dirsOnly: Boolean = false,
    ): FileListOutcome
}

internal data class FileReadOutcome(
    val path: String,
    val size: Long,
    val lineCount: Int?,
    val startLine: Int,
    val endLine: Int,
    val content: String?,
    val metadataOnly: Boolean,
    val error: String? = null,
)

internal data class FileWriteOutcome(
    val path: String,
    val bytesWritten: Int,
    val error: String? = null,
)

internal data class FileReplaceOutcome(
    val path: String,
    val occurrences: Int = 0,
    val lineNumbers: List<Int> = emptyList(),
    val error: String? = null,
)

internal data class FileGrepMatch(
    val path: String,
    val line: Int,
    val text: String,
)

internal data class FileGrepOutcome(
    val pattern: String,
    val matches: List<FileGrepMatch>,
    val truncated: Boolean,
    val error: String? = null,
    val scannedFiles: Int = 0,
    val skippedOversize: Int = 0,
    val skippedBinary: Int = 0,
    val ignoredDirs: List<String> = emptyList(),
    val truncatedReason: String? = null,
    val remaining: Int = 0,
    val remainingIsLowerBound: Boolean = false,
    val offset: Int = 0,
    val engine: String = FileSearchPolicy.ENGINE_JAVA,
    val incomplete: Boolean = false,
    val phase: String = FileSearchPolicy.PHASE_DONE,
    val note: String? = null,
)

internal data class FileListEntry(
    val path: String,
    val type: String,
    val size: Long? = null,
    val mtimeMs: Long? = null,
)

internal data class FileListOutcome(
    val entries: List<FileListEntry>,
    val truncated: Boolean,
    val error: String? = null,
    val truncatedReason: String? = null,
    val remaining: Int = 0,
    val remainingIsLowerBound: Boolean = false,
    val offset: Int = 0,
)

internal data class FileGlobOutcome(
    val paths: List<String>,
    val truncated: Boolean,
    val error: String? = null,
    val ignoredDirs: List<String> = emptyList(),
    val truncatedReason: String? = null,
    val remaining: Int = 0,
    val remainingIsLowerBound: Boolean = false,
    val offset: Int = 0,
    val incomplete: Boolean = false,
    val phase: String = FileSearchPolicy.PHASE_DONE,
    val note: String? = null,
)
