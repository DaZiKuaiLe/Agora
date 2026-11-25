package com.newoether.agora.tool

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.newoether.agora.sandbox.SandboxSharedStorageAccess
import java.io.File
import java.net.URI

/**
 * Classifies and opens a local file or an http(s) URL via the system viewer / installer.
 */
internal object OpenFile {
    const val TOOL = "open_file"
    const val APK_MIME = "application/vnd.android.package-archive"

    sealed class Target {
        data class Http(val url: String) : Target()
        data class Local(val path: String) : Target()
    }

    fun classify(raw: String): Result<Target> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("path is required"))
        }
        val scheme = schemeOf(trimmed) ?: return Result.success(Target.Local(trimmed))
        if (scheme != "http" && scheme != "https") {
            return Result.failure(
                IllegalArgumentException("Only http/https URLs or a local file path are allowed"),
            )
        }
        val uri = runCatching { URI(trimmed) }.getOrElse {
            return Result.failure(IllegalArgumentException("Invalid URL"))
        }
        if (uri.host.isNullOrBlank()) {
            return Result.failure(IllegalArgumentException("Invalid URL"))
        }
        return Result.success(Target.Http(trimmed))
    }

    fun mimeType(path: String): String {
        val ext = path.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        if (ext.isEmpty() || ext == path.lowercase()) return "application/octet-stream"
        if (ext == "apk") return APK_MIME
        val mapped = runCatching {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        }.getOrNull()
        return mapped ?: extraMime(ext) ?: "application/octet-stream"
    }

    fun isApk(path: String): Boolean = path.substringAfterLast('.', "").equals("apk", ignoreCase = true)

    fun launch(context: Context?, raw: String): Result<Opened> {
        val target = classify(raw).getOrElse { return Result.failure(it) }
        val app = context ?: return Result.failure(
            IllegalStateException("Cannot open files without app context"),
        )
        return when (target) {
            is Target.Http -> launchHttp(app, target.url)
            is Target.Local -> launchLocal(app, target.path)
        }
    }

    private fun launchHttp(context: Context, url: String): Result<Opened> {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return start(context, intent).map {
            Opened(path = url, kind = "http", mime = httpMime(url))
        }
    }

    private fun launchLocal(context: Context, path: String): Result<Opened> {
        val locator = LocalFileLocator(context)
        val file = locator.resolve(path).getOrElse { return Result.failure(it) }
        locator.permissionError(file)?.let {
            return Result.failure(SecurityException(it))
        }
        if (!file.isFile) {
            return Result.failure(IllegalArgumentException(localRejection(file, path)))
        }
        if (!file.canRead() && SandboxSharedStorageAccess.isGranted(context).not()) {
            return Result.failure(
                SecurityException(
                    "Cannot access shared storage. Call request_all_files_access or grant all-files access in Remote Terminals settings first.",
                ),
            )
        }
        val mime = mimeType(file.name)
        val uri = contentUri(context, file).getOrElse { return Result.failure(it) }
        val apk = isApk(file.name)
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            clipData = ClipData.newRawUri("", uri)
        }
        return start(context, view).map {
            Opened(
                path = file.absolutePath,
                kind = if (apk) "apk" else "file",
                mime = mime,
            )
        }
    }

    private fun contentUri(context: Context, file: File): Result<Uri> {
        val authority = "${context.packageName}.fileprovider"
        runCatching { FileProvider.getUriForFile(context, authority, file) }
            .onSuccess { return Result.success(it) }
        return runCatching {
            val dir = File(context.cacheDir, "shared").apply { mkdirs() }
            val dest = File(dir, "open-${file.name}")
            file.copyTo(dest, overwrite = true)
            FileProvider.getUriForFile(context, authority, dest)
        }
    }

    private fun start(context: Context, intent: Intent): Result<Unit> = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }.recoverCatching { error ->
        if (error is ActivityNotFoundException) {
            throw IllegalStateException("No app can open this file")
        }
        throw error
    }

    private fun schemeOf(raw: String): String? {
        val idx = raw.indexOf(':')
        if (idx <= 0) return null
        val scheme = raw.substring(0, idx)
        if (scheme.isEmpty() || !scheme[0].isLetter()) return null
        if (scheme.any { !it.isLetterOrDigit() && it != '+' && it != '.' && it != '-' }) return null
        // Windows drive: C:\... or C:/...
        if (scheme.length == 1) return null
        return scheme.lowercase()
    }

    internal fun httpMime(url: String): String {
        val path = runCatching { URI(url).path }.getOrNull().orEmpty()
        return mimeType(path.substringAfterLast('/'))
    }

    internal fun localRejection(file: File, requestedPath: String): String {
        if (file.exists() && file.isDirectory) return "Not a file: ${file.path}"
        return if (looksLikeRemoteWindows(requestedPath)) {
            "File not found: ${file.path}. Remote files must be downloaded with download_file first."
        } else {
            "File not found: ${file.path}"
        }
    }

    private fun looksLikeRemoteWindows(path: String): Boolean {
        val n = path.replace('\\', '/')
        return Regex("^[A-Za-z]:/").containsMatchIn(n) || Regex("^/[A-Za-z]:/").containsMatchIn(n)
    }

    private fun extraMime(ext: String): String? = when (ext) {
        "pdf" -> "application/pdf"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "txt" -> "text/plain"
        "html", "htm" -> "text/html"
        "md", "markdown" -> "text/markdown"
        "json" -> "application/json"
        "mp4" -> "video/mp4"
        "mp3" -> "audio/mpeg"
        "zip" -> "application/zip"
        else -> null
    }

    data class Opened(val path: String, val kind: String, val mime: String)
}
