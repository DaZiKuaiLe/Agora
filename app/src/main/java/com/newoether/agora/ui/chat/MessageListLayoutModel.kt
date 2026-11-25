package com.newoether.agora.ui.chat

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageGenerationBoundaryResolver
import com.newoether.agora.model.Participant
import com.newoether.agora.util.Constants

internal enum class MessageListLayoutMode {
    STABLE,
    ACTIVE_SCROLL,
    COVERED_TRANSITION,
}

internal fun messageListLayoutMode(
    isSwitching: Boolean,
    isScrollInProgress: Boolean,
): MessageListLayoutMode = when {
    isSwitching -> MessageListLayoutMode.COVERED_TRANSITION
    isScrollInProgress -> MessageListLayoutMode.ACTIVE_SCROLL
    else -> MessageListLayoutMode.STABLE
}

internal fun calculateTailMinHeightPx(
    viewportHeightPx: Int,
    targetTopPx: Int,
    bottomObstructionPx: Int,
): Int = (viewportHeightPx - targetTopPx - bottomObstructionPx).coerceAtLeast(0)

internal fun calculateTailLayoutHeightPx(
    minimumHeightPx: Int,
    contentHeightPx: Int,
): Int = maxOf(minimumHeightPx, contentHeightPx)

/**
 * Extra LazyColumn after-content padding that replaces embedding [minimumHeightPx] into the last
 * turn item. Padding is not part of LazyList's average item size, so a follow-bottom blank region
 * cannot make a fast fling skip whole earlier turns.
 */
internal fun calculateFollowBottomExtraPaddingPx(
    minimumHeightPx: Int,
    lastTurnContentHeightPx: Int,
): Int = (minimumHeightPx - lastTurnContentHeightPx.coerceAtLeast(0)).coerceAtLeast(0)

internal fun measuredTurnContentHeightPx(
    turn: MessageListTurn?,
    messageHeights: Map<String, Int>,
): Int {
    if (turn == null) return 0
    var sum = 0
    for (message in turn.messages) {
        val height = messageHeights[message.id] ?: continue
        if (height > 0) sum += height
    }
    return sum
}

/**
 * One stable LazyColumn item per conversation turn.
 *
 * A USER starts a turn and every following non-USER message remains in that turn until the next
 * USER. This identity must not change when a new turn is appended: otherwise the previous
 * assistant is disposed from the tail item and recreated as a standalone item, producing a
 * visible blank/reparse frame on Send.
 */
internal data class MessageListTurn(
    val key: String,
    val messages: List<ChatMessage>,
)

internal fun regenerationExitMessageIds(
    messages: List<ChatMessage>,
    oldMessageId: String,
): Set<String> = regenerationExitMessages(messages, oldMessageId)
    .mapTo(linkedSetOf()) { message -> message.id }

internal fun regenerationExitMessages(
    messages: List<ChatMessage>,
    oldMessageId: String,
): List<ChatMessage> {
    val firstExitIndex = messages.indexOfFirst { message -> message.id == oldMessageId }
    if (firstExitIndex < 0) return emptyList()
    return messages.subList(firstExitIndex, messages.size).toList()
}

/**
 * Keeps the faded branch composed after the selected graph path switches to the replacement.
 * Current-path messages are ordered first so SENDING appears directly below its USER anchor;
 * retained messages keep their original stable keys after it and contribute layout height only.
 */
internal fun mergeRegenerationPresentationMessages(
    activeMessages: List<ChatMessage>,
    retainedExitMessages: List<ChatMessage>,
): List<ChatMessage> {
    if (retainedExitMessages.isEmpty()) return activeMessages
    val activeIds = activeMessages.mapTo(hashSetOf()) { message -> message.id }
    val retainedOnly = retainedExitMessages.filterNot { message -> message.id in activeIds }
    if (retainedOnly.isEmpty()) return activeMessages
    return buildList(activeMessages.size + retainedOnly.size) {
        addAll(activeMessages)
        addAll(retainedOnly)
    }
}

internal data class PendingEditVisualReplacement(
    val sourceMessageId: String,
    val sourceParentId: String?,
    val submittedText: String,
    val stableVisualKey: String,
)

internal fun resolvePendingEditReplacement(
    messages: List<ChatMessage>,
    pending: PendingEditVisualReplacement?,
): ChatMessage? {
    pending ?: return null
    if (messages.any { message -> message.id == pending.sourceMessageId }) return null
    return messages.lastOrNull { message ->
        MessageGenerationBoundaryResolver.isRealUser(message) &&
            message.id != pending.sourceMessageId &&
            message.parentId == pending.sourceParentId &&
            message.text == pending.submittedText
    }
}

/**
 * Reuses a turn only when every message is still the same instance. Text growth replaces the
 * tail turn; a copied historical message gets a new turn so stale text cannot stick.
 */
internal class MessageListTurnCache {
    private var previousByKey: Map<String, MessageListTurn> = emptyMap()

    fun update(messages: List<ChatMessage>): List<MessageListTurn> {
        val next = buildMessageListTurns(messages) { key, grouped ->
            previousByKey[key]?.takeIf { previous ->
                sameMessageInstances(previous.messages, grouped)
            }
        }
        previousByKey = next.associateBy { it.key }
        return next
    }
}

private fun sameMessageInstances(
    previous: List<ChatMessage>,
    grouped: List<ChatMessage>,
): Boolean {
    if (previous.size != grouped.size) return false
    for (index in previous.indices) {
        if (previous[index] !== grouped[index]) return false
    }
    return true
}

/**
 * Session-scoped one-shot registry. LazyColumn disposal/recreation and conversation switches must
 * not replay an entrance for a message the user has already seen.
 */
internal class MessageLifecycleAppearanceRegistry {
    private val knownMessageIds = HashSet<String>()

    fun isKnown(messageId: String): Boolean = messageId in knownMessageIds

    fun markKnown(messageId: String) {
        knownMessageIds += messageId
    }
}

internal fun shouldAnimateMessageLifecycleEntrance(
    message: ChatMessage,
    isKnown: Boolean,
    isLoading: Boolean,
    isStreaming: Boolean,
    lastUserMessageId: String?,
    requestedTargetMessageId: String?,
): Boolean {
    if (isKnown) return false
    if (
        message.id.startsWith(Constants.TOOL_MSG_PREFIX) ||
        message.id.startsWith(Constants.RESULT_MSG_PREFIX)
    ) {
        return false
    }
    return when (message.participant) {
        Participant.USER ->
            message.id == requestedTargetMessageId ||
                (isLoading && message.id == lastUserMessageId)
        Participant.MODEL ->
            isStreaming ||
                (
                    requestedTargetMessageId != null &&
                        message.parentId == requestedTargetMessageId
                )
        Participant.ERROR -> false
    }
}

internal fun buildMessageListTurns(
    messages: List<ChatMessage>,
    reuse: ((key: String, grouped: List<ChatMessage>) -> MessageListTurn?)? = null,
): List<MessageListTurn> {
    if (messages.isEmpty()) return emptyList()

    val turns = mutableListOf<MessageListTurn>()
    val activeTurn = ArrayList<ChatMessage>()

    fun flushActiveTurn() {
        if (activeTurn.isEmpty()) return
        val key = activeTurn.first().id
        turns += reuse?.invoke(key, activeTurn)
            ?: MessageListTurn(key, activeTurn.toList())
        activeTurn.clear()
    }

    messages.forEach { message ->
        if (MessageGenerationBoundaryResolver.isRealUser(message)) {
            flushActiveTurn()
            activeTurn += message
        } else if (
            activeTurn.firstOrNull()?.let(MessageGenerationBoundaryResolver::isRealUser) == true
        ) {
            activeTurn += message
        } else {
            // Preserve leading/error-only paths as their own stable items until a USER begins a
            // normal conversation turn.
            flushActiveTurn()
            activeTurn += message
            flushActiveTurn()
        }
    }
    flushActiveTurn()
    return turns
}

/**
 * Top-bar turn total. Streaming text does not move it. A new user turn, a deletion,
 * or a branch switch changes message identity and recounts those boundaries only.
 */
internal class MessageListTurnCountCache {
    private var ids: Array<String> = emptyArray()
    private var participants: IntArray = IntArray(0)
    private var count: Int = 0
    private var ready: Boolean = false

    fun countOf(messages: List<ChatMessage>): Int {
        if (matches(messages)) return count
        count = buildMessageListTurns(messages).size
        ids = Array(messages.size) { index -> messages[index].id }
        participants = IntArray(messages.size) { index -> messages[index].participant.ordinal }
        ready = true
        return count
    }

    private fun matches(messages: List<ChatMessage>): Boolean {
        if (!ready || messages.size != ids.size) return false
        for (index in messages.indices) {
            val message = messages[index]
            if (message.id != ids[index]) return false
            if (message.participant.ordinal != participants[index]) return false
        }
        return true
    }
}

/** Branch/run chrome ignores text growth. The token changes only when that identity changes. */
internal class RunProjectionIdentityCache {
    private var previous: List<ChatMessage> = emptyList()
    private var token: Int = 0
    private var ready: Boolean = false

    fun tokenOf(messages: List<ChatMessage>): Int {
        if (ready && sameRunProjectionIdentity(previous, messages)) return token
        previous = messages
        token += 1
        ready = true
        return token
    }
}

internal fun sameRunProjectionIdentity(
    previous: List<ChatMessage>,
    next: List<ChatMessage>,
): Boolean {
    if (previous.size != next.size) return false
    for (index in previous.indices) {
        val before = previous[index]
        val after = next[index]
        if (before === after) continue
        if (
            before.id != after.id ||
            before.parentId != after.parentId ||
            before.participant != after.participant ||
            before.timestamp != after.timestamp ||
            before.runId != after.runId ||
            before.runSequence != after.runSequence
        ) {
            return false
        }
    }
    return true
}

internal fun messageListTurnIndex(
    turns: List<MessageListTurn>,
    messageId: String,
): Int = turns.indexOfFirst { turn -> turn.messages.any { it.id == messageId } }

/** 1-based turn under the top of the viewport. Approximate; sentinel maps to the last turn. */
internal data class MessageListTurnProgress(val current: Int, val total: Int)

internal fun visibleMessageListTurnProgress(
    firstVisibleItemIndex: Int,
    turnCount: Int,
): MessageListTurnProgress? {
    if (turnCount <= 0) return null
    return MessageListTurnProgress(
        current = firstVisibleItemIndex.coerceIn(0, turnCount - 1) + 1,
        total = turnCount,
    )
}

internal fun estimateMessageListTurnHeightPx(
    turn: MessageListTurn,
    messageHeights: Map<String, Int>,
    fallbackHeightPx: Float,
): Float = turn.messages.sumOf { message ->
    (messageHeights[message.id]?.toDouble() ?: fallbackHeightPx.toDouble())
}.toFloat()

/**
 * Vertical offset from the turn item's top to the start (top) of [messageId] within that turn.
 * Used when jumping so reading begins at the message head, not the turn's geometric center.
 */
internal fun estimateMessageStartInTurnPx(
    turn: MessageListTurn,
    messageId: String,
    messageHeights: Map<String, Int>,
    fallbackHeightPx: Float,
): Float {
    val targetIndex = turn.messages.indexOfFirst { it.id == messageId }
    if (targetIndex <= 0) return 0f
    return turn.messages
        .take(targetIndex)
        .sumOf { message ->
            (messageHeights[message.id]?.toDouble() ?: fallbackHeightPx.toDouble())
        }
        .toFloat()
}

internal fun estimateSearchMatchCenterInTurnPx(
    turn: MessageListTurn,
    match: ConversationSearchMatch,
    messageHeights: Map<String, Int>,
    fallbackHeightPx: Float,
): Float {
    val targetIndex = turn.messages.indexOfFirst { it.id == match.messageId }
    if (targetIndex < 0) return fallbackHeightPx / 2f
    val precedingHeight = turn.messages
        .take(targetIndex)
        .sumOf { message ->
            (messageHeights[message.id]?.toDouble() ?: fallbackHeightPx.toDouble())
        }
        .toFloat()
    val target = turn.messages[targetIndex]
    val targetHeight = messageHeights[target.id]?.toFloat() ?: fallbackHeightPx
    val characterCenter = (match.start + match.endExclusive) / 2f
    val textFraction = if (target.text.isEmpty()) {
        0.5f
    } else {
        (characterCenter / target.text.length).coerceIn(0.08f, 0.92f)
    }
    return precedingHeight + targetHeight * textFraction
}

internal data class MessageListViewportAnchor(
    val messageId: String,
    val scrollOffsetPx: Int,
)

internal class MessageListMutationAnchorLock {
    private val activeMutationKeys = mutableSetOf<String>()

    var anchor: MessageListViewportAnchor? = null
        private set

    fun begin(
        key: String,
        candidate: MessageListViewportAnchor?,
    ): MessageListViewportAnchor? {
        activeMutationKeys += key
        if (anchor == null) anchor = candidate
        return anchor
    }

    /**
     * Returns the anchor exactly once, when the final overlapping mutation settles.
     * Repeated begin calls for the same reversing animation never replace the pre-change anchor.
     */
    fun finish(key: String): MessageListViewportAnchor? {
        if (!activeMutationKeys.remove(key) || activeMutationKeys.isNotEmpty()) return null
        return anchor.also { anchor = null }
    }

    fun cancel() {
        activeMutationKeys.clear()
        anchor = null
    }

    val activeMutationCount: Int
        get() = activeMutationKeys.size
}
