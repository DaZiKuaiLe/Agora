package com.newoether.agora.util

/**
 * Grep/list roots. A file path is the sole target; a directory is expanded by the caller.
 */
internal object FileSearchRoots {
    fun of(path: String, isDirectory: Boolean): List<String>? {
        val normalized = path.trim()
        if (normalized.isEmpty()) return null
        if (isDirectory) return null
        return listOf(normalized)
    }
}
