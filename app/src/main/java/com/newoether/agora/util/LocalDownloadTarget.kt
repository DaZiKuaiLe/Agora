package com.newoether.agora.util

import android.content.Context
import com.newoether.agora.sandbox.SandboxSharedStorageAccess
import java.io.File

/**
 * Resolves the local destination for SFTP downloads and whether a local upload source is readable.
 * Shared-storage paths require [SandboxSharedStorageAccess] (MANAGE_EXTERNAL_STORAGE).
 */
object LocalDownloadTarget {
    fun resolve(
        targetDir: String,
        defaultDir: File,
        context: Context?,
    ): Result<File> {
        if (targetDir.isBlank()) return Result.success(defaultDir)
        val requested = runCatching { File(targetDir).canonicalFile }
            .getOrElse { return Result.failure(IllegalArgumentException("Invalid target_dir")) }
        val defaultCanonical = runCatching { defaultDir.canonicalFile }.getOrNull()
        if (defaultCanonical != null && requested.path.startsWith(defaultCanonical.path)) {
            return Result.success(requested)
        }
        val appFiles = context?.filesDir?.canonicalFile
        if (appFiles != null && requested.path.startsWith(appFiles.path)) {
            return Result.success(requested)
        }
        if (isLockout(requested)) {
            return Result.failure(
                SecurityException("target_dir is not accessible: ${requested.path}"),
            )
        }
        if (context == null || !SandboxSharedStorageAccess.isGranted(context)) {
            return Result.failure(
                SecurityException(
                    "target_dir is outside the app download folder. Call request_all_files_access or grant all-files access in Remote Terminals settings first.",
                ),
            )
        }
        return Result.success(requested)
    }

    fun uploadSourceReadable(localPath: String, context: Context?): Result<File> {
        val file = File(localPath)
        if (!file.isFile) {
            return Result.failure(IllegalArgumentException("local file not found: $localPath"))
        }
        if (file.canRead()) return Result.success(file)
        if (context != null && SandboxSharedStorageAccess.isGranted(context) && file.exists()) {
            return Result.success(file)
        }
        return Result.failure(
            SecurityException(
                "Cannot read local_path. Call request_all_files_access or grant all-files access in Remote Terminals settings to use shared storage.",
            ),
        )
    }

    internal fun isLockout(file: File): Boolean {
        val path = file.path.replace('\\', '/')
        return path.contains("/Android/data") ||
            path.contains("/Android/obb") ||
            path.contains("/data/user/") ||
            path.contains("/data/data/")
    }
}
