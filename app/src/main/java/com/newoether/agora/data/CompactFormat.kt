package com.newoether.agora.data

/**
 * App-wide compact/projection contract version.
 * Bump when working-context projection or summary input changes (forces full optimize).
 * Do not bump when the user edits active memory or a default prompt.
 */
object CompactFormat {
    const val CURRENT_REV = 5
}

internal fun compactFormatStale(
    storedRev: Int,
    hasCompactArtifacts: Boolean,
    currentRev: Int = CompactFormat.CURRENT_REV,
): Boolean = hasCompactArtifacts && storedRev < currentRev
