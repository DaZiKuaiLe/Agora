package com.newoether.agora.util

/**
 * OpenSSH sftp-server (including Windows) uses POSIX path syntax. JSch treats `\` as a glob
 * escape and anything not starting with `/` as relative to the SFTP cwd (usually home).
 * This converts user/model-supplied remote paths into the form SFTP actually understands.
 */
object SftpRemotePath {
    private val driveAbs = Regex("^/?([A-Za-z]):(/.*)?$")
    private val driveRoot = Regex("^/[A-Za-z]:$")

    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return trimmed
        var path = trimmed.replace('\\', '/')
        path = path.replace(Regex("(?<!:)/{2,}"), "/")
        val drive = driveAbs.matchEntire(path) ?: return path
        val letter = drive.groupValues[1].uppercase()
        val rest = drive.groupValues[2]
        return if (rest.isEmpty()) "/$letter:" else "/$letter:$rest"
    }

    fun fileName(raw: String): String {
        val normalized = normalize(raw)
        val name = normalized.substringAfterLast('/').trim()
        return name.ifBlank { "download" }
    }

    fun parent(raw: String): String {
        val normalized = normalize(raw).trimEnd('/')
        if (normalized.isEmpty() || isDriveRoot(normalized)) return ""
        val cut = normalized.lastIndexOf('/')
        if (cut <= 0) return ""
        return normalized.substring(0, cut)
    }

    fun isDriveRoot(path: String): Boolean = driveRoot.matches(path)

    fun sizeMismatch(expected: Long, actual: Long, path: String): String? =
        if (expected == actual) {
            null
        } else {
            "SFTP transfer failed: size mismatch remote=$actual local=$expected path=$path"
        }
}
