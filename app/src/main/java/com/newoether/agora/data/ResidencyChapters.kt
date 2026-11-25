package com.newoether.agora.data

/** Storage delimiter; never shown on the working-model wire. */
internal const val RESIDENCY_CHAPTER_STORAGE_MARK = "<<<AGORA_CHAPTER>>>"

/** Visible splice between integration segments on the working-model wire. */
internal const val RESIDENCY_CHAPTER_WIRE_BREAK =
    "\n\n—— 整合段分隔（按时间先后；各段都是历史，可能重复或口径不同）——\n\n"

internal const val RESIDENCY_SEGMENT_HEADER_PREFIX = "整合段"

private val segmentHeaderCount = Regex("""\[(?:整合段|冻结章) \d+/(\d+)]""")

internal fun parseResidencyChapters(stored: String): List<String> {
    val trimmed = stored.trim()
    if (trimmed.isEmpty()) return emptyList()
    if (RESIDENCY_CHAPTER_STORAGE_MARK !in stored) return listOf(trimmed)
    return stored.split(RESIDENCY_CHAPTER_STORAGE_MARK).map { it.trim() }.filter { it.isNotEmpty() }
}

internal fun encodeResidencyChapters(chapters: List<String>): String {
    val cleaned = chapters.map { it.trim() }.filter { it.isNotEmpty() }
    if (cleaned.isEmpty()) return ""
    if (cleaned.size == 1) return cleaned.single()
    return cleaned.joinToString(RESIDENCY_CHAPTER_STORAGE_MARK)
}

internal fun formatResidencyChaptersForWire(chapters: List<String>): String {
    val cleaned = chapters.map { it.trim() }.filter { it.isNotEmpty() }
    if (cleaned.isEmpty()) return ""
    if (cleaned.size == 1) return cleaned.single()
    return cleaned.mapIndexed { index, chapter ->
        "[$RESIDENCY_SEGMENT_HEADER_PREFIX ${index + 1}/${cleaned.size}]\n$chapter"
    }.joinToString(RESIDENCY_CHAPTER_WIRE_BREAK)
}

/** 0 when there is a single segment (or empty). */
internal fun freezeChapterCount(chapters: List<String>): Int =
    if (chapters.size >= 2) chapters.size else 0

internal fun freezeChapterCountFromWire(text: String): Int {
    val declared = segmentHeaderCount.find(text.trim())?.groupValues?.getOrNull(1)?.toIntOrNull()
    if (declared != null && declared >= 2) return declared
    return freezeChapterCount(parseResidencyChapters(text))
}
