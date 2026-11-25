package com.newoether.agora.util

/**
 * Fixed noise-directory prune for recursive glob/grep. Does not read .gitignore.
 * Matching is the directory name only (case-insensitive).
 */
internal object FileSearchIgnore {
    val DIR_NAMES = setOf(
        ".git",
        ".svn",
        ".hg",
        "node_modules",
        "bower_components",
        "__pycache__",
        ".venv",
        "venv",
        ".tox",
        ".mypy_cache",
        ".pytest_cache",
        ".gradle",
        ".next",
        "dist",
        "build",
        "out",
        "target",
        "logs",
        "log",
        ".cache",
        "cache",
        "gpucache",
        "code cache",
        "cache_data",
        "appdata",
        "site-packages",
        "coverage",
        ".nvm",
    )

    const val WALK_FILE_CAP = 800
    const val MAX_IGNORED_DIR_REPORT = 20
    const val REASON_WALK = "walk_cap"
    const val REASON_TIME = "time_budget"

    fun shouldIgnoreDir(
        name: String,
        noIgnore: Boolean,
        extra: Collection<String> = emptyList(),
    ): Boolean {
        if (noIgnore || name.isEmpty() || name == "." || name == "..") return false
        val key = name.lowercase()
        if (key in DIR_NAMES) return true
        return extra.any { it.equals(name, ignoreCase = true) }
    }

    /**
     * Ignore names apply to directories under [root], not to [root] itself.
     * Grep of `.../logs/record.log` must not skip the file because an ancestor is named logs.
     */
    fun pathIgnoredUnderRoot(
        root: String,
        fullPath: String,
        noIgnore: Boolean,
        extra: Collection<String> = emptyList(),
    ): Boolean {
        if (noIgnore) return false
        val rootN = normalizeSlashes(root)
        val fullN = normalizeSlashes(fullPath)
        if (rootN.isEmpty() || fullN.equals(rootN, ignoreCase = true)) return false
        val prefix = "$rootN/"
        if (!fullN.startsWith(prefix, ignoreCase = true)) return false
        return fullN.substring(prefix.length).split('/').any { part ->
            part.isNotEmpty() && shouldIgnoreDir(part, false, extra)
        }
    }

    fun normalizeSlashes(path: String): String =
        path.trim().replace('\\', '/').trimEnd('/')

    fun rememberIgnored(ignored: MutableSet<String>, name: String) {
        if (ignored.size >= MAX_IGNORED_DIR_REPORT) return
        ignored.add(name)
    }

    fun mergedNames(extra: Collection<String>): List<String> {
        val seen = linkedSetOf<String>()
        DIR_NAMES.forEach { seen.add(it) }
        extra.forEach { name ->
            val trimmed = name.trim()
            if (trimmed.isNotEmpty()) seen.add(trimmed.lowercase())
        }
        return seen.toList()
    }
}

internal data class FileWalkResult(
    val files: List<String>,
    val ignoredDirs: List<String> = emptyList(),
    val truncatedReason: String? = null,
)
