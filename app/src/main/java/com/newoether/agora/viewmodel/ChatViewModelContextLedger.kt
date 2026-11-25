package com.newoether.agora.viewmodel

import com.newoether.agora.model.ChatMessage
import kotlinx.coroutines.flow.StateFlow

internal val ChatViewModel.isLastTranscriptPage: StateFlow<Boolean>
    get() = conversationUi.isLastPage

internal val ChatViewModel.hasEarlierTranscriptPage: StateFlow<Boolean>
    get() = conversationUi.hasEarlierPage

internal val ChatViewModel.earlierTranscriptPageGeneration: StateFlow<Int>
    get() = conversationUi.earlierPageGeneration

internal val ChatViewModel.transcriptLedgerCursor: StateFlow<String>
    get() = conversationUi.ledgerCursor

internal suspend fun ChatViewModel.showLastTranscriptPage() {
    conversationUi.showLastPage()
}

internal fun ChatViewModel.showEarlierTranscriptPage() {
    conversationUi.requestEarlierPage()
}

internal suspend fun ChatViewModel.persistSendTokenCache(
    conversationId: String,
    cache: SendTokenCache,
) = conversationUi.persistSendTokenCache(conversationId, cache)

internal suspend fun ChatViewModel.loadMessageDetail(messageId: String): ChatMessage? =
    conversationUi.workingSet.detailMessage(messageId)
