package com.newoether.agora.viewmodel

import com.newoether.agora.data.local.UiMessageGraphRow

/**
 * Top-bar total. The visible page is not the source of this number.
 * A null [seated] value with no [previous] map is the one-time seat from identity counts.
 * After that, only the difference between [previous] and [next] moves the total.
 */
internal fun advanceTranscriptTokenTotal(
    seated: Int?,
    previous: Map<String, Int>?,
    next: Map<String, Int>,
): Pair<Int, Map<String, Int>> {
    if (previous == null) {
        val total = seated ?: next.values.sumOf { it.toLong() }.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        return total to next
    }
    val delta = next.values.sumOf { it.toLong() } - previous.values.sumOf { it.toLong() }
    val total = ((seated ?: 0).toLong() + delta).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    return total to next
}

/** Tool and result rows are blanked in the UI projection, so they do not add to the total. */
internal fun countedTranscriptTokens(rows: List<UiMessageGraphRow>): Map<String, Int> {
    val counts = LinkedHashMap<String, Int>(rows.size)
    for (row in rows) {
        if (row.id.isTranscriptSyntheticRow()) continue
        counts[row.id] = row.tokenCount
    }
    return counts
}
