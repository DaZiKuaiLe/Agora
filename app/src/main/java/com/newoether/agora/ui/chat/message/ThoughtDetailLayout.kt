package com.newoether.agora.ui.chat.message

internal const val THOUGHT_DETAIL_CHUNK_CODE_POINTS = 480
internal const val THOUGHT_DETAIL_THROTTLE_MS = 120

internal fun usesChunkedThoughtDetail(
    selectedSegmentCount: Int,
    segmentType: String,
    hasDirectMarkdown: Boolean = false,
): Boolean = selectedSegmentCount == 1 && usesPlainThoughtDetail(segmentType, hasDirectMarkdown)

internal fun thoughtDetailChunks(
    content: String,
    chunkCodePoints: Int = THOUGHT_DETAIL_CHUNK_CODE_POINTS,
): List<String> {
    if (content.isEmpty()) return emptyList()
    if (chunkCodePoints <= 0) return listOf(content)
    val chunks = ArrayList<String>()
    var index = 0
    val length = content.length
    while (index < length) {
        val start = index
        var codePoints = 0
        var lastBreak = -1
        while (index < length && codePoints < chunkCodePoints) {
            val codePoint = content.codePointAt(index)
            val size = Character.charCount(codePoint)
            index += size
            codePoints += 1
            if (codePoint == '\n'.code) lastBreak = index
        }
        if (index < length && lastBreak > start) index = lastBreak
        chunks += content.substring(start, index)
    }
    return chunks
}
