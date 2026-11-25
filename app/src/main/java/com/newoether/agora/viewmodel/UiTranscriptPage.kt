package com.newoether.agora.viewmodel

import com.newoether.agora.data.local.UiMessageGraphRow
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.util.Constants

/**
 * One screen of the transcript. The full text stays in Room.
 *
 * TODO: search must query Room for hits and open the page that contains the hit.
 * Do not scan the loaded page, and do not load the rest of the transcript to search.
 */
internal const val UI_TRANSCRIPT_PAGE_TURNS = 5

internal data class TranscriptTurnSpan(
    val startIndex: Int,
    val endInclusive: Int,
)

internal data class TranscriptPageSlice(
    val chain: List<UiMessageGraphRow>,
    val hasEarlier: Boolean,
    val isLastPage: Boolean,
    /** Turn index where this page starts. The previous page ends here. */
    val startTurnExclusive: Int,
)

internal fun String.isTranscriptSyntheticRow(): Boolean =
    startsWith(Constants.TOOL_MSG_PREFIX) || startsWith(Constants.RESULT_MSG_PREFIX)

internal fun UiMessageGraphRow.isTranscriptTurnStart(): Boolean =
    participant == Participant.USER &&
        !id.isTranscriptSyntheticRow() &&
        !id.startsWith(Constants.COMPACT_MSG_PREFIX)

/** Selected branch, including the synthetic rows the visible walk steps through. */
internal fun selectedTranscriptChain(
    rows: List<UiMessageGraphRow>,
    selectedChildren: Map<String?, String>,
): List<UiMessageGraphRow> {
    if (rows.isEmpty()) return emptyList()
    val byParent = rows.groupBy { it.parentId }
    val path = ArrayList<UiMessageGraphRow>()
    val seen = HashSet<String>()
    var cursor: String? = null
    while (true) {
        val siblings = byParent[cursor]
        if (siblings.isNullOrEmpty()) break
        val selectedId = selectedChildren[cursor]
        val visible = siblings.filter { !it.id.isTranscriptSyntheticRow() }
        val pool = if (visible.isNotEmpty()) visible else siblings
        val selected = pool.find { it.id == selectedId } ?: pool.sortedBy { it.timestamp }.last()
        if (!seen.add(selected.id)) break
        path += selected
        cursor = selected.id
    }
    return path
}

internal fun transcriptTurnSpans(chain: List<UiMessageGraphRow>): List<TranscriptTurnSpan> {
    if (chain.isEmpty()) return emptyList()
    val starts = chain.indices.filter { chain[it].isTranscriptTurnStart() }
    if (starts.isEmpty()) return listOf(TranscriptTurnSpan(0, chain.lastIndex))
    return starts.indices.map { index ->
        val from = if (index == 0) 0 else starts[index]
        val to = if (index == starts.lastIndex) chain.lastIndex else starts[index + 1] - 1
        TranscriptTurnSpan(from, to)
    }
}

/**
 * [pageEndTurnExclusive] null is the last page. Otherwise it is the first turn that is
 * not included, so the previous page can be addressed without reading later bodies.
 */
internal fun transcriptPage(
    chain: List<UiMessageGraphRow>,
    pageEndTurnExclusive: Int?,
    pageTurns: Int = UI_TRANSCRIPT_PAGE_TURNS,
): TranscriptPageSlice {
    val spans = transcriptTurnSpans(chain)
    if (spans.isEmpty()) {
        return TranscriptPageSlice(emptyList(), hasEarlier = false, isLastPage = true, startTurnExclusive = 0)
    }
    val end = (pageEndTurnExclusive ?: spans.size).coerceIn(0, spans.size)
    if (end == 0) {
        return TranscriptPageSlice(emptyList(), hasEarlier = false, isLastPage = false, startTurnExclusive = 0)
    }
    val start = (end - pageTurns.coerceAtLeast(1)).coerceAtLeast(0)
    val from = spans[start].startIndex
    val to = spans[end - 1].endInclusive
    return TranscriptPageSlice(
        chain = chain.subList(from, to + 1).toList(),
        hasEarlier = start > 0,
        isLastPage = end == spans.size,
        startTurnExclusive = start,
    )
}

internal fun UiMessageGraphRow.toTranscriptStub(): ChatMessage = ChatMessage(
    id = id,
    parentId = parentId,
    text = "",
    status = status,
    participant = participant,
    timestamp = timestamp,
    runId = runId,
    turnId = turnId,
    compactBatchId = compactBatchId,
    compactDigestRev = compactDigestRev,
    tokenCount = if (id.isTranscriptSyntheticRow()) 0 else tokenCount,
)

/** Off-path siblings of this page so branch arrows still count. Bodies stay on disk. */
internal fun siblingTranscriptStubs(
    rows: List<UiMessageGraphRow>,
    chain: List<UiMessageGraphRow>,
): List<ChatMessage> {
    if (chain.isEmpty()) return emptyList()
    val chainIds = chain.mapTo(HashSet(chain.size)) { it.id }
    val parentIds = chain.mapTo(HashSet(chain.size)) { it.parentId }
    return rows.mapNotNull { row ->
        if (row.id in chainIds || row.id.isTranscriptSyntheticRow()) return@mapNotNull null
        if (row.parentId !in parentIds) return@mapNotNull null
        row.toTranscriptStub()
    }
}

internal fun transcriptLedgerIdentity(chain: List<UiMessageGraphRow>): Int {
    var hash = 1
    for (row in chain) {
        if (row.status.isStreamingUiStatus() || row.id.isTranscriptSyntheticRow()) continue
        hash = 31 * hash + row.id.hashCode()
        hash = 31 * hash + (row.parentId?.hashCode() ?: 0)
        hash = 31 * hash + row.participant.ordinal
        hash = 31 * hash + row.status.ordinal
        hash = 31 * hash + row.compactDigestRev
        hash = 31 * hash + (row.compactBatchId?.hashCode() ?: 0)
        hash = 31 * hash + (row.turnId?.hashCode() ?: 0)
    }
    return hash
}

internal fun transcriptLedgerCursor(chain: List<UiMessageGraphRow>): String {
    val visible = chain.mapNotNull { row ->
        if (row.id.isTranscriptSyntheticRow() || row.status.isStreamingUiStatus()) null
        else row.toTranscriptStub()
    }
    return sendLedgerCursor(visible)
}
