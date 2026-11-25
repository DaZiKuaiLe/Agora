package com.newoether.agora.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import com.newoether.agora.model.ChatMessage

/**
 * Returns the LazyColumn turn index for [messageId], or -1 when blank / missing.
 * Does not scroll — [MessageList] owns jump scrolling so it can cancel mutation anchors.
 */
internal fun resolveJumpTurnIndex(
    messageId: String,
    messages: List<ChatMessage>,
): Int {
    val targetId = messageId.trim()
    if (targetId.isEmpty()) return -1
    return messageListTurnIndex(buildMessageListTurns(messages), targetId)
}

/** Instantly places the turn at [turnIndex] at the top of the padded content band. */
internal suspend fun jumpChatListToTurn(
    listState: LazyListState,
    turnIndex: Int,
) {
    listState.scrollToItem(index = turnIndex, scrollOffset = 0)
}
