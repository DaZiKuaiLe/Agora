package com.newoether.agora.tool

import com.newoether.agora.data.TimeBandPolicy
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageGenerationBoundaryResolver
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.isContextCompact
import com.newoether.agora.util.Constants

/** One finished dialogue turn for summary-batch planning / projection. */
data class CompactTurn(
    val turnId: String,
    val locked: Boolean,
    val batchId: String?,
    val summary: String?,
    val messages: List<ChatMessage>,
    /** Assistant row that owns turnId / lock / batch fields; user row if the turn was interrupted. */
    val anchorId: String,
    /** Working-context digest body stored on this turn (newest batched turn holds latest rev). */
    val digest: String? = null,
    val digestRev: Int = 0,
    val integrateText: String? = null,
) {
    val batched: Boolean get() = !batchId.isNullOrBlank()
}

object CompactTurnIndexer {
    /**
     * Index provider-visible path into turns (oldest → newest).
     * Capsule / protocol-only rows that are not part of a real user/assistant boundary are skipped.
     */
    fun index(path: List<ChatMessage>): List<CompactTurn> {
        val visible = path.filterNot { it.isContextCompact() }
        val boundaries = MessageGenerationBoundaryResolver.resolve(visible)
        return compactTurnsFromGroups(boundaries.map { it.messages }, allowUserOnly = false)
    }

    /**
     * One dialogue turn: real user opening through the next opening (tool loops stay in the
     * same turn). An interrupted opening with no assistant is still a turn. Synthetic API users
     * (`image_context_`, projection ids, …) do not start a turn.
     */
    fun indexDialogueTurns(path: List<ChatMessage>): List<CompactTurn> {
        val visible = path.filterNot { it.isContextCompact() }
        val groups = mutableListOf<List<ChatMessage>>()
        val current = mutableListOf<ChatMessage>()
        fun flush() {
            if (current.isEmpty()) return
            groups += current.toList()
            current.clear()
        }
        visible.forEach { message ->
            if (isDialogueTurnOpening(message) && current.isNotEmpty()) {
                flush()
            }
            current += message
        }
        flush()
        return compactTurnsFromGroups(mergeLeadingProtocol(groups), allowUserOnly = true)
    }

    /**
     * Finished dialogue turns for archive batching / projection. Drops the live tail so it does
     * not consume the original-band retain budget.
     */
    fun finishedDialogueTurns(path: List<ChatMessage>): List<CompactTurn> {
        val turns = indexDialogueTurns(path)
        if (turns.isEmpty()) return turns
        return if (turns.last().isLiveTail()) turns.dropLast(1) else turns
    }

    fun isDialogueTurnOpening(message: ChatMessage): Boolean {
        if (!MessageGenerationBoundaryResolver.isRealUser(message)) return false
        val id = message.id
        if (id.startsWith("context_batch_") ||
            id.startsWith("context_summary_") ||
            id.startsWith("context_digest_") ||
            id.startsWith(TimeBandPolicy.TURN_INTEGRATION_ID_PREFIX)
        ) {
            return false
        }
        if (id.startsWith(IMAGE_CONTEXT_PREFIX)) return false
        if (id.startsWith(PROTOCOL_NOTICE_PREFIX)) return false
        if (id.startsWith(API_INITIAL_USER_PREFIX)) return false
        return true
    }

    private fun mergeLeadingProtocol(groups: List<List<ChatMessage>>): List<List<ChatMessage>> {
        if (groups.size < 2) return groups
        val leading = groups.first()
        if (leading.any(::isDialogueTurnOpening)) return groups
        return listOf(leading + groups[1]) + groups.drop(2)
    }

    private fun compactTurnsFromGroups(
        groups: List<List<ChatMessage>>,
        allowUserOnly: Boolean,
    ): List<CompactTurn> =
        groups.mapNotNull { messages ->
            val opening = messages.firstOrNull(::isDialogueTurnOpening)
            val assistant = messages.lastOrNull(MessageGenerationBoundaryResolver::isOrdinaryAssistant)
            val anchor = assistant ?: opening?.takeIf { allowUserOnly } ?: return@mapNotNull null
            if (anchor.id.startsWith(Constants.COMPACT_MSG_PREFIX)) return@mapNotNull null
            val turnId = anchor.turnId?.takeIf { it.isNotBlank() } ?: "pending_${anchor.id}"
            CompactTurn(
                turnId = turnId,
                locked = anchor.compactLocked,
                batchId = anchor.compactBatchId?.takeIf { it.isNotBlank() },
                summary = anchor.compactSummary?.takeIf { it.isNotBlank() },
                messages = messages,
                anchorId = anchor.id,
                digest = anchor.compactDigest?.takeIf { it.isNotBlank() },
                digestRev = anchor.compactDigestRev,
                integrateText = (assistant?.integrateText ?: opening?.integrateText)
                    ?.takeIf { it.isNotBlank() },
            )
        }

    fun toPlannerRefs(turns: List<CompactTurn>): List<CompactBatchPlanner.TurnRef> =
        turns.map {
            CompactBatchPlanner.TurnRef(
                turnId = it.turnId,
                locked = it.locked,
                batched = it.batched,
            )
        }

    private const val IMAGE_CONTEXT_PREFIX = "image_context_"
    private const val PROTOCOL_NOTICE_PREFIX = "protocol_notice_"
    private const val API_INITIAL_USER_PREFIX = "api_initial_user_"
}

internal fun CompactTurn.isReadyToIntegrate(): Boolean =
    messages.none { message ->
        !message.id.startsWith(Constants.TOOL_MSG_PREFIX) &&
            !message.id.startsWith(Constants.RESULT_MSG_PREFIX) &&
            message.status.isGenerationInProgress()
    }

internal fun CompactTurn.isLiveTail(): Boolean =
    !isReadyToIntegrate() ||
        messages.none(MessageGenerationBoundaryResolver::isOrdinaryAssistant)

private fun MessageStatus.isGenerationInProgress(): Boolean = when (this) {
    MessageStatus.TRANSCRIBING,
    MessageStatus.SENDING,
    MessageStatus.THINKING,
    MessageStatus.TOOL_CALLING -> true
    MessageStatus.SUCCESS,
    MessageStatus.STOPPED,
    MessageStatus.ERROR -> false
}
