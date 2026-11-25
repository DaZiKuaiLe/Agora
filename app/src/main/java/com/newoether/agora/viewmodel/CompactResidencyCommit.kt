package com.newoether.agora.viewmodel

import com.newoether.agora.api.util.ContextTokenEstimator
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.tool.CompactTurn

/**
 * Disk write for a compact that has already finished its model calls. The conversation's
 * previous markers and occupancy number stay in place until this returns.
 */
internal suspend fun commitFinishedCompact(
    conversations: ConversationRepository,
    conversationId: String,
    turns: List<CompactTurn>,
    clearExistingBatches: Boolean,
    coveredTurnIds: List<String>,
    batchId: String,
    digestTurnId: String?,
    digest: String?,
    digestRev: Int,
    formatRev: Int?,
    summaryTokens: Int,
    keptOriginalTokens: Int,
): SendTokenCache {
    val previous = conversations.getSendTokenCache(conversationId)
    val cursor = cursorAfterCompactCommit(
        turns = turns,
        clearExistingBatches = clearExistingBatches,
        coveredTurnIds = coveredTurnIds.toSet(),
        digestTurnId = digestTurnId,
        digestRev = digestRev,
        batchId = batchId,
    )
    val cache = cacheAfterCompactCommit(
        previous = previous,
        summaryTokens = summaryTokens,
        keptOriginalTokens = keptOriginalTokens,
        nextCursor = cursor,
    )
    conversations.commitCompactResidency(
        conversationId = conversationId,
        clearExistingBatches = clearExistingBatches,
        coveredTurnIds = coveredTurnIds,
        batchId = batchId,
        digestTurnId = digestTurnId,
        digest = digest,
        digestRev = digestRev,
        formatRev = formatRev,
        cache = cache,
    )
    return cache
}

internal fun compactChapterTokens(chapters: List<String>): Int =
    chapters.fold(0L) { total, chapter ->
        (total + ContextTokenEstimator.estimateText(chapter).toLong())
            .coerceAtMost(Int.MAX_VALUE.toLong())
    }.toInt()
