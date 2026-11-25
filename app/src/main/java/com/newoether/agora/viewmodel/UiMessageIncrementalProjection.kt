package com.newoether.agora.viewmodel

import com.newoether.agora.data.local.UiMessageGraphRow
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus

internal data class UiMessageGraphFingerprint(
    val parentId: String?,
    val status: MessageStatus,
    val compactDigestRev: Int,
    val compactBatchId: String?,
    val turnId: String?,
)

internal data class ProjectedUiMessage(
    val fingerprint: UiMessageGraphFingerprint,
    val message: ChatMessage,
)

internal fun UiMessageGraphRow.fingerprint(): UiMessageGraphFingerprint =
    UiMessageGraphFingerprint(
        parentId = parentId,
        status = status,
        compactDigestRev = compactDigestRev,
        compactBatchId = compactBatchId,
        turnId = turnId,
    )

internal fun MessageStatus.isStreamingUiStatus(): Boolean =
    this == MessageStatus.TRANSCRIBING ||
        this == MessageStatus.SENDING ||
        this == MessageStatus.THINKING ||
        this == MessageStatus.TOOL_CALLING

/**
 * Disk stays authoritative. Memory only fetches rows whose graph fingerprint changed, plus new
 * ids. The live overlay row is never pulled back from Room while a stream owns it.
 */
internal fun incrementalUiMessageIdsToFetch(
    rows: List<UiMessageGraphRow>,
    previous: Map<String, ProjectedUiMessage>,
    existingById: Map<String, ChatMessage>,
    streamingMessageId: String?,
): List<String> = rows.mapNotNull { row ->
    if (row.id == streamingMessageId) return@mapNotNull null
    val stored = existingById[row.id]
    if (
        stored != null &&
        !stored.status.isStreamingUiStatus() &&
        stored.status == row.status &&
        stored.parentId == row.parentId &&
        stored.compactDigestRev == row.compactDigestRev &&
        stored.compactBatchId == row.compactBatchId &&
        stored.turnId == row.turnId
    ) {
        return@mapNotNull null
    }
    val previousRow = previous[row.id]
    if (previousRow != null && previousRow.fingerprint == row.fingerprint()) {
        return@mapNotNull null
    }
    row.id
}

internal fun assembleIncrementalUiMessages(
    rows: List<UiMessageGraphRow>,
    previous: Map<String, ProjectedUiMessage>,
    existingById: Map<String, ChatMessage>,
    decoded: Map<String, ChatMessage>,
    streamingMessageId: String?,
): Pair<List<ChatMessage>, Map<String, ProjectedUiMessage>> {
    val next = ArrayList<ChatMessage>(rows.size)
    val nextMap = LinkedHashMap<String, ProjectedUiMessage>(rows.size)
    for (row in rows) {
        val fingerprint = row.fingerprint()
        val message = decoded[row.id]
            ?: existingById[row.id]
            ?: previous[row.id]?.message
            ?: continue
        if (row.id == streamingMessageId && message.status.isStreamingUiStatus()) {
            // Overlay owns the live body; keep the graph slot for parent-path walking only.
        }
        next += message
        nextMap[row.id] = ProjectedUiMessage(fingerprint, message)
    }
    return next to nextMap
}

internal fun reuseProjectedMessageList(
    previous: List<ChatMessage>,
    next: List<ChatMessage>,
): List<ChatMessage> {
    if (previous.size == next.size && previous.indices.all { previous[it] === next[it] }) {
        return previous
    }
    return next
}
