package com.newoether.agora.viewmodel

import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Cache-aside for heavy message fields. Room stays authoritative. */
internal class ConversationWorkingSet(
    private val conversations: ConversationRepository,
    private val toMessage: (MessageEntity) -> ChatMessage,
) {
    private val lock = Mutex()
    private val details = linkedMapOf<String, ChatMessage>()

    suspend fun detailMessage(messageId: String): ChatMessage? = lock.withLock {
        details[messageId]?.let { return@withLock it }
        val loaded = conversations.getMessagesByIds(listOf(messageId))
            .firstOrNull()
            ?.let(toMessage)
            ?: return@withLock null
        details[messageId] = loaded
        while (details.size > MAX_DETAILS) {
            details.remove(details.keys.first())
        }
        loaded
    }

    suspend fun thought(messageId: String): String? =
        detailMessage(messageId)?.thoughts?.takeIf { it.isNotBlank() }
            ?: conversations.getMessageThoughts(messageId)?.takeIf { it.isNotBlank() }

    suspend fun invalidate(messageId: String) {
        lock.withLock { details.remove(messageId) }
    }

    suspend fun clear() {
        lock.withLock { details.clear() }
    }

    private companion object {
        const val MAX_DETAILS = 8
    }
}

/** Keep a collapsed thought chip when Room omitted the thought body. */
internal fun ChatMessage.withThoughtPlaceholderIfNeeded(): ChatMessage {
    if (segments.orEmpty().any { it.type == "thought" && it.content.isNotBlank() }) {
        return this
    }
    if (thoughtTimeMs == null && thoughtTitle.isNullOrBlank()) {
        return this
    }
    val placeholder = MessageSegment(
        type = "thought",
        content = "\u200b",
        durationMs = thoughtTimeMs,
    )
    return copy(segments = segments.orEmpty() + placeholder)
}
