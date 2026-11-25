package com.newoether.agora.tool

import com.newoether.agora.util.FileSearchIgnore

/**
 * Page size vs scan budget, incomplete-result flags, and model-facing routing copy.
 */
internal object FileSearchPolicy {
    const val WALK_FILE_CAP = FileSearchIgnore.WALK_FILE_CAP
    const val GREP_TIME_BUDGET_MS = 30_000L

    const val REASON_PAGE = "page_cap"
    const val REASON_FILE = "file_cap"
    const val REASON_WALK = FileSearchIgnore.REASON_WALK
    const val REASON_TIME = FileSearchIgnore.REASON_TIME

    const val PHASE_WALK = "walk"
    const val PHASE_SCAN = "scan"
    const val PHASE_DONE = "done"

    const val ENGINE_JAVA = "java"
    const val ENGINE_GREP = "grep"
    const val ENGINE_POWERSHELL = "powershell"

    const val TYPE_FILE = "file"
    const val TYPE_DIR = "dir"

    const val INCOMPLETE_NOTE =
        "no matches yet; walk/search did not finish — do not treat as absent"

    val POLICY =
        "Look at one folder (files and subfolder names) with list_local_files / list_remote_files. " +
            "Find file names with glob_local_files / glob_remote_files (glob; recursive). " +
            "Search file contents with grep_local / grep_remote (regex; recursive; glob only filters files). " +
            "Do not use execute_* or rg/ls/find as a file API. " +
            "Glob and grep skip noise directories such as node_modules, .git, and Cache unless no_ignore is true. " +
            "If remaining > 0, narrow path/glob or pass offset for the next page. " +
            "If incomplete is true or a note says the walk did not finish, empty matches do not mean absent."

    fun globSearchPattern(raw: String): String {
        val glob = raw.ifBlank { "*" }
        return if (glob.contains('/')) glob else "**/$glob"
    }

    fun shouldIgnoreDir(
        name: String,
        noIgnore: Boolean,
        extra: Collection<String> = emptyList(),
    ): Boolean = FileSearchIgnore.shouldIgnoreDir(name, noIgnore, extra)

    fun rememberIgnored(ignored: MutableSet<String>, name: String) {
        FileSearchIgnore.rememberIgnored(ignored, name)
    }

    fun pageOffset(raw: Int?): Int = raw?.coerceAtLeast(0) ?: 0

    fun isIncomplete(scanReason: String?): Boolean =
        scanReason != null && scanReason != REASON_PAGE

    fun phase(scanReason: String?, progressed: Int): String {
        if (scanReason == null || scanReason == REASON_PAGE) return PHASE_DONE
        if (scanReason == REASON_WALK) return PHASE_WALK
        if (scanReason == REASON_FILE) return PHASE_SCAN
        if (scanReason == REASON_TIME) return if (progressed == 0) PHASE_WALK else PHASE_SCAN
        return PHASE_SCAN
    }

    fun emptyIncompleteNote(empty: Boolean, incomplete: Boolean): String? =
        if (empty && incomplete) INCOMPLETE_NOTE else null

    fun finishGrep(
        pattern: String,
        page: List<FileGrepMatch>,
        totalMatches: Int,
        offset: Int,
        scannedFiles: Int,
        skippedOversize: Int,
        skippedBinary: Int,
        ignoredDirs: List<String>,
        scanReason: String?,
        engine: String = ENGINE_JAVA,
        error: String? = null,
    ): FileGrepOutcome {
        val remaining = (totalMatches - offset - page.size).coerceAtLeast(0)
        val truncated = remaining > 0 || scanReason != null
        val truncatedReason = scanReason ?: if (remaining > 0) REASON_PAGE else null
        val incomplete = isIncomplete(scanReason)
        return FileGrepOutcome(
            pattern = pattern,
            matches = page,
            truncated = truncated,
            error = error,
            scannedFiles = scannedFiles,
            skippedOversize = skippedOversize,
            skippedBinary = skippedBinary,
            ignoredDirs = ignoredDirs,
            truncatedReason = truncatedReason,
            remaining = remaining,
            remainingIsLowerBound = incomplete,
            offset = offset,
            engine = engine,
            incomplete = incomplete,
            phase = phase(scanReason, scannedFiles),
            note = emptyIncompleteNote(page.isEmpty(), incomplete),
        )
    }

    fun finishGlob(
        page: List<String>,
        totalMatched: Int,
        offset: Int,
        ignoredDirs: List<String>,
        scanReason: String?,
        walked: Int = totalMatched,
        error: String? = null,
    ): FileGlobOutcome {
        val remaining = (totalMatched - offset - page.size).coerceAtLeast(0)
        val truncated = remaining > 0 || scanReason != null
        val truncatedReason = scanReason ?: if (remaining > 0) REASON_PAGE else null
        val incomplete = isIncomplete(scanReason)
        return FileGlobOutcome(
            paths = page,
            truncated = truncated,
            error = error,
            ignoredDirs = ignoredDirs,
            truncatedReason = truncatedReason,
            remaining = remaining,
            remainingIsLowerBound = incomplete,
            offset = offset,
            incomplete = incomplete,
            phase = phase(scanReason, walked),
            note = emptyIncompleteNote(page.isEmpty(), incomplete),
        )
    }

    fun finishList(
        page: List<FileListEntry>,
        totalMatched: Int,
        offset: Int,
        scanReason: String? = null,
        error: String? = null,
    ): FileListOutcome {
        val remaining = (totalMatched - offset - page.size).coerceAtLeast(0)
        val truncated = remaining > 0 || scanReason != null
        val truncatedReason = scanReason ?: if (remaining > 0) REASON_PAGE else null
        return FileListOutcome(
            entries = page,
            truncated = truncated,
            error = error,
            truncatedReason = truncatedReason,
            remaining = remaining,
            remainingIsLowerBound = isIncomplete(scanReason),
            offset = offset,
        )
    }

    fun sortListEntries(entries: List<FileListEntry>): List<FileListEntry> =
        entries.sortedWith(
            compareBy<FileListEntry> { if (it.type == TYPE_DIR) 0 else 1 }
                .thenBy { it.path.lowercase() },
        )
}
