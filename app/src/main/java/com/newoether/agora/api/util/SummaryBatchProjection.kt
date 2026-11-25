package com.newoether.agora.api.util

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.isContextCompact
import com.newoether.agora.model.isSuccessfulContextCompact
import com.newoether.agora.tool.CompactTurnIndexer
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Provider-context compression projection:
 * - legacy capsules via [applyNearestContextCompact] when no batch summaries are active;
 * - working-context digest when a path digest exists;
 * - summary_batches JSON as fallback when batches exist but digest never succeeded.
 */
fun applyContextCompression(
    messages: List<ChatMessage>,
    projectSummaryBatches: Boolean,
    retainTurns: Int = 0,
    archiveBandEnabled: Boolean = com.newoether.agora.data.TimeBandPolicy.ARCHIVE_BAND_ENABLED,
): List<ChatMessage> {
    if (!archiveBandEnabled) {
        return applyNearestContextCompact(messages)
    }
    val hasBatchSummaries = CompactTurnIndexer.finishedDialogueTurns(messages).any { it.batched }
    if (projectSummaryBatches && hasBatchSummaries) {
        val digest = resolvePathDigest(messages)
        if (digest != null) {
            return applyDigestProjection(messages, retainTurns, digest)
        }
        return applySummaryBatchProjection(messages, retainTurns)
    }
    return applyNearestContextCompact(messages)
}

internal const val SUMMARY_BATCHES_TYPE = "summary_batches"

@Serializable
internal data class SummaryBatchesPayload(
    val type: String = SUMMARY_BATCHES_TYPE,
    val batches: List<SummaryBatchItem>,
)

@Serializable
internal data class SummaryBatchItem(
    val batchId: String,
    val summary: String,
)

private val summaryBatchesJson = Json {
    prettyPrint = true
    encodeDefaults = true
    ignoreUnknownKeys = true
}

internal fun encodeSummaryBatchesJson(batches: List<SummaryBatchItem>): String =
    summaryBatchesJson.encodeToString(
        SummaryBatchesPayload(type = SUMMARY_BATCHES_TYPE, batches = batches),
    )

/** Parses a wire summary-batches JSON body into readable separator sections; null if not that shape. */
internal fun formatSummaryBatchesForReading(text: String): String? {
    val payload = runCatching {
        summaryBatchesJson.decodeFromString<SummaryBatchesPayload>(text.trim())
    }.getOrNull() ?: return null
    if (payload.type != SUMMARY_BATCHES_TYPE || payload.batches.isEmpty()) return null
    return buildString {
        payload.batches.forEachIndexed { index, item ->
            if (index > 0) append("\n\n")
            append("======== 批次 ")
            append(index + 1)
            append(" / batchId=")
            append(item.batchId)
            append(" ========\n\n")
            append(item.summary.trim())
            append("\n\n（原文在细节库；需要时 query_detail_store，detail_id 用上面的 batchId）")
        }
    }
}

/**
 * Replace consecutive batched turns outside the trailing retain window with one USER row whose
 * body is summary-batches JSON. Locked turns and the retain window stay as originals.
 *
 * Compact turns require a finished assistant, so an in-flight trailing user (the message being
 * generated from) is not indexed — append that path suffix verbatim so OpenAI-compatible
 * validators still see a user/tool-terminated history.
 */
fun applySummaryBatchProjection(
    messages: List<ChatMessage>,
    retainTurns: Int,
): List<ChatMessage> {
    val withoutFailedCapsules = messages.filterNot {
        it.isContextCompact() && !it.isSuccessfulContextCompact()
    }
    // Legacy successful capsules are retired once batch summaries exist on the path.
    val path = withoutFailedCapsules.filterNot { it.isContextCompact() }
    val turns = CompactTurnIndexer.finishedDialogueTurns(path)
    if (turns.isEmpty()) return path

    val retain = retainTurns.coerceAtLeast(0)
    val retainFrom = (turns.size - retain).coerceAtLeast(0)
    val emittedBatchIds = mutableSetOf<String>()
    val result = mutableListOf<ChatMessage>()
    val pending = mutableListOf<SummaryBatchItem>()
    var pendingTimestamp = 0L
    var pendingFirstBatchId: String? = null

    fun flushPending() {
        if (pending.isEmpty()) return
        val firstId = pendingFirstBatchId ?: pending.first().batchId
        result += ChatMessage(
            id = "context_batch_$firstId",
            text = encodeSummaryBatchesJson(pending.toList()),
            participant = Participant.USER,
            status = MessageStatus.SUCCESS,
            timestamp = pendingTimestamp,
        )
        pending.clear()
        pendingFirstBatchId = null
    }

    turns.forEachIndexed { index, turn ->
        val inRetain = index >= retainFrom
        if (inRetain || !turn.batched) {
            flushPending()
            result += turn.messages
            return@forEachIndexed
        }
        val batchId = turn.batchId!!
        if (emittedBatchIds.add(batchId)) {
            if (pending.isEmpty()) {
                pendingFirstBatchId = batchId
                pendingTimestamp = turn.messages.firstOrNull()?.timestamp ?: 0L
            }
            pending += SummaryBatchItem(
                batchId = batchId,
                summary = turn.summary.orEmpty().trim(),
            )
        }
    }
    flushPending()
    val lastCoveredId = turns.last().messages.lastOrNull()?.id
    if (lastCoveredId != null) {
        val lastCoveredIndex = path.indexOfLast { it.id == lastCoveredId }
        if (lastCoveredIndex in 0 until path.lastIndex) {
            result += path.subList(lastCoveredIndex + 1, path.size)
        }
    }
    return result
}

fun pathHasSummaryBatches(messages: List<ChatMessage>): Boolean =
    CompactTurnIndexer.finishedDialogueTurns(messages).any { it.batched }

/** Original-band retain plus archive digest / summary_batches, or integration, per test flags. */
fun applyTimeBandProjection(
    messages: List<ChatMessage>,
    retainTurns: Int,
): List<ChatMessage> = when {
    com.newoether.agora.data.TimeBandPolicy.ARCHIVE_BAND_ENABLED -> applyContextCompression(
        messages = messages,
        projectSummaryBatches = pathHasSummaryBatches(messages),
        retainTurns = retainTurns,
    )
    com.newoether.agora.data.TimeBandPolicy.INTEGRATION_BAND_ENABLED ->
        applyIntegrationProjection(messages, retainTurns)
    else -> messages
}

internal fun isContextProjectionMessageId(id: String): Boolean =
    id.startsWith("context_batch_") ||
        id.startsWith("context_summary_") ||
        id.startsWith(CONTEXT_DIGEST_ID_PREFIX) ||
        id.startsWith(com.newoether.agora.data.TimeBandPolicy.TURN_INTEGRATION_ID_PREFIX)
