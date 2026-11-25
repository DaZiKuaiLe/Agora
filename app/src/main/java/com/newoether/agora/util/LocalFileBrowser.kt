package com.newoether.agora.util

import android.content.Context
import android.os.Environment
import com.newoether.agora.sandbox.SandboxSharedStorageAccess
import java.io.File

data class LocalFileRoot(
    val id: String,
    val labelRes: Int,
    val directory: File,
)

enum class LocalFileMediaKind {
    NONE,
    IMAGE,
    VIDEO,
}

enum class LocalFileSort {
    NAME_ASC,
    NAME_DESC,
    MODIFIED_DESC,
    MODIFIED_ASC,
    SIZE_DESC,
    SIZE_ASC,
}

data class LocalFileEntry(
    val name: String,
    val file: File,
    val isDirectory: Boolean,
    val sizeBytes: Long?,
    val lastModified: Long,
    val mediaKind: LocalFileMediaKind,
)

/**
 * Path-based directory listing for the in-app file picker (absolute filesystem paths).
 * Shared-storage roots require [SandboxSharedStorageAccess]; app-private [Context.getFilesDir]
 * is always available.
 */
object LocalFileBrowser {
    const val ROOT_APP = "app"
    const val ROOT_INTERNAL = "internal"
    const val ROOT_DOWNLOADS = "downloads"
    const val ROOT_DOCUMENTS = "documents"
    const val ROOT_DCIM = "dcim"

    private val IMAGE_EXT = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif",
    )
    private val VIDEO_EXT = setOf(
        "mp4", "mkv", "webm", "3gp", "3gpp", "mov", "m4v", "avi",
    )

    fun roots(context: Context): List<LocalFileRoot> {
        val result = ArrayList<LocalFileRoot>(5)
        result += LocalFileRoot(
            id = ROOT_APP,
            labelRes = com.newoether.agora.R.string.local_file_root_app,
            directory = context.filesDir,
        )
        if (!SandboxSharedStorageAccess.isGranted(context)) return result
        val external = Environment.getExternalStorageDirectory()
        result += LocalFileRoot(
            id = ROOT_INTERNAL,
            labelRes = com.newoether.agora.R.string.local_file_root_internal,
            directory = external,
        )
        result += LocalFileRoot(
            id = ROOT_DOWNLOADS,
            labelRes = com.newoether.agora.R.string.local_file_root_downloads,
            directory = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS,
            ),
        )
        result += LocalFileRoot(
            id = ROOT_DOCUMENTS,
            labelRes = com.newoether.agora.R.string.local_file_root_documents,
            directory = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOCUMENTS,
            ),
        )
        result += LocalFileRoot(
            id = ROOT_DCIM,
            labelRes = com.newoether.agora.R.string.local_file_root_dcim,
            directory = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DCIM,
            ),
        )
        return result
    }

    fun resolveRoot(context: Context, rootId: String): LocalFileRoot? =
        roots(context).firstOrNull { it.id == rootId }

    fun defaultRoot(context: Context): LocalFileRoot {
        val available = roots(context)
        return available.firstOrNull { it.id == ROOT_INTERNAL }
            ?: available.firstOrNull { it.id == ROOT_DOWNLOADS }
            ?: available.first()
    }

    fun mediaKindFor(file: File): LocalFileMediaKind {
        if (file.isDirectory) return LocalFileMediaKind.NONE
        val ext = file.extension.lowercase()
        return when {
            ext in IMAGE_EXT -> LocalFileMediaKind.IMAGE
            ext in VIDEO_EXT -> LocalFileMediaKind.VIDEO
            else -> LocalFileMediaKind.NONE
        }
    }

    fun isPreviewable(entry: LocalFileEntry): Boolean =
        !entry.isDirectory &&
            (entry.mediaKind == LocalFileMediaKind.IMAGE ||
                entry.mediaKind == LocalFileMediaKind.VIDEO)

    /**
     * Lists children of [directory] that are safe to show in the picker.
     * Returns failure when the directory cannot be read.
     */
    fun listEntries(
        directory: File,
        sort: LocalFileSort = LocalFileSort.NAME_ASC,
        showHidden: Boolean = false,
    ): Result<List<LocalFileEntry>> {
        val canonical = runCatching { directory.canonicalFile }
            .getOrElse { return Result.failure(it) }
        if (!canonical.isDirectory) {
            return Result.failure(IllegalArgumentException("Not a directory"))
        }
        if (isPickerLockout(canonical)) {
            return Result.failure(SecurityException("Directory is not accessible"))
        }
        val children = canonical.listFiles()
            ?: return Result.failure(SecurityException("Unable to list directory"))
        val entries = children.mapNotNull { child ->
            toEntryOrNull(child, showHidden)
        }
        return Result.success(sortEntries(entries, sort))
    }

    /**
     * Case-insensitive name search under [directory] (recursive). Respects picker lockouts.
     * Stops early when [maxResults] is reached or [isActive] returns false.
     */
    fun searchUnder(
        directory: File,
        query: String,
        sort: LocalFileSort = LocalFileSort.NAME_ASC,
        maxResults: Int = 500,
        showHidden: Boolean = false,
        isActive: () -> Boolean = { true },
    ): Result<List<LocalFileEntry>> {
        val needle = query.trim()
        if (needle.isEmpty()) return Result.success(emptyList())
        val root = runCatching { directory.canonicalFile }
            .getOrElse { return Result.failure(it) }
        if (!root.isDirectory) {
            return Result.failure(IllegalArgumentException("Not a directory"))
        }
        if (isPickerLockout(root)) {
            return Result.failure(SecurityException("Directory is not accessible"))
        }
        val needleLower = needle.lowercase()
        val found = ArrayList<LocalFileEntry>(minOf(64, maxResults))
        val stack = ArrayDeque<File>()
        stack.add(root)
        while (stack.isNotEmpty() && found.size < maxResults && isActive()) {
            val dir = stack.removeFirst()
            val children = dir.listFiles() ?: continue
            for (child in children) {
                if (!isActive() || found.size >= maxResults) break
                if (!showHidden && child.name.startsWith('.')) continue
                if (isPickerLockout(child)) continue
                if (child.isDirectory) {
                    stack.addLast(child)
                }
                if (child.name.lowercase().contains(needleLower)) {
                    toEntryOrNull(child, showHidden)?.let { found += it }
                }
            }
        }
        return Result.success(sortEntries(found, sort))
    }

    fun parentWithinRoot(current: File, root: File): File? {
        val currentCanonical = runCatching { current.canonicalFile }.getOrNull() ?: return null
        val rootCanonical = runCatching { root.canonicalFile }.getOrNull() ?: return null
        if (currentCanonical.path == rootCanonical.path) return null
        val parent = currentCanonical.parentFile ?: return null
        val parentCanonical = runCatching { parent.canonicalFile }.getOrNull() ?: return null
        if (!parentCanonical.path.startsWith(rootCanonical.path)) return null
        return parentCanonical
    }

    fun isUnderRoot(file: File, root: File): Boolean {
        val filePath = runCatching { file.canonicalFile.path }.getOrNull() ?: return false
        val rootPath = runCatching { root.canonicalFile.path }.getOrNull() ?: return false
        return filePath == rootPath || filePath.startsWith("$rootPath/")
    }

    /** Blocks Android/data and Android/obb; app-private files stay reachable via the app root. */
    fun isPickerLockout(file: File): Boolean {
        val path = file.path.replace('\\', '/')
        return path.contains("/Android/data") || path.contains("/Android/obb")
    }

    private fun toEntryOrNull(child: File, showHidden: Boolean): LocalFileEntry? {
        if (!showHidden && child.name.startsWith('.')) return null
        if (isPickerLockout(child)) return null
        val isDir = child.isDirectory
        return LocalFileEntry(
            name = child.name,
            file = child,
            isDirectory = isDir,
            sizeBytes = if (isDir) null else child.length().takeIf { child.isFile },
            lastModified = child.lastModified(),
            mediaKind = mediaKindFor(child),
        )
    }

    internal fun sortEntries(
        entries: List<LocalFileEntry>,
        sort: LocalFileSort,
    ): List<LocalFileEntry> {
        val byNameAsc = compareBy<LocalFileEntry> { it.name.lowercase() }
        val byNameDesc = compareByDescending<LocalFileEntry> { it.name.lowercase() }
        val byModifiedAsc = compareBy<LocalFileEntry> { it.lastModified }
        val byModifiedDesc = compareByDescending<LocalFileEntry> { it.lastModified }
        val bySizeAsc = compareBy<LocalFileEntry> { it.sizeBytes ?: -1L }
        val bySizeDesc = compareByDescending<LocalFileEntry> { it.sizeBytes ?: -1L }
        val secondary = when (sort) {
            LocalFileSort.NAME_ASC -> byNameAsc
            LocalFileSort.NAME_DESC -> byNameDesc
            LocalFileSort.MODIFIED_ASC -> byModifiedAsc.then(byNameAsc)
            LocalFileSort.MODIFIED_DESC -> byModifiedDesc.then(byNameAsc)
            LocalFileSort.SIZE_ASC -> bySizeAsc.then(byNameAsc)
            LocalFileSort.SIZE_DESC -> bySizeDesc.then(byNameAsc)
        }
        return entries.sortedWith(compareBy<LocalFileEntry> { !it.isDirectory }.then(secondary))
    }
}
