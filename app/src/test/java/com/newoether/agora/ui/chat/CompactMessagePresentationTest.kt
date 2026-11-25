package com.newoether.agora.ui.chat

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageGenerationBoundaryResolver
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.ui.chat.bottombar.contextUsageAtCapacity
import com.newoether.agora.ui.chat.bottombar.contextUsageExceedsCompactThreshold
import com.newoether.agora.ui.chat.message.ContextCompactPillPresentation
import com.newoether.agora.ui.chat.message.SegmentSheetBackAction
import com.newoether.agora.ui.chat.message.contextCompactPillPresentation
import com.newoether.agora.ui.chat.message.segmentSheetBackAction
import com.newoether.agora.ui.chat.message.thoughtDetailChunks
import com.newoether.agora.ui.chat.message.usesChunkedThoughtDetail
import com.newoether.agora.ui.chat.message.usesPlainThoughtDetail
import com.newoether.agora.ui.chat.message.usesVirtualizedSegmentDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactMessagePresentationTest {
    @Test
    fun compactMessagesRemainInTurnsEvenWhenTheirSummaryIsBlank() {
        val compact = message("compact_boundary", Participant.MODEL).copy(text = "")

        val leading = buildMessageListTurns(listOf(compact))
        val insideTurn = buildMessageListTurns(
            listOf(message("user", Participant.USER), compact)
        )

        assertEquals(listOf("compact_boundary"), leading.single().messages.map { it.id })
        assertEquals(
            listOf("user", "compact_boundary"),
            insideTurn.single().messages.map { it.id },
        )
    }

    @Test
    fun legacyUserCompactDoesNotCreateAUserFullPageTurn() {
        val legacyCompact = message("compact_boundary", Participant.USER)

        val turns = buildMessageListTurns(
            listOf(
                message("user", Participant.USER),
                message("assistant", Participant.MODEL),
                legacyCompact,
                message("later-assistant", Participant.MODEL),
            ),
        )

        assertFalse(MessageGenerationBoundaryResolver.isRealUser(legacyCompact))
        assertEquals(1, turns.size)
        assertEquals(
            listOf("user", "assistant", "compact_boundary", "later-assistant"),
            turns.single().messages.map { it.id },
        )
    }

    @Test
    fun processCompletionNoticeIsARealUserTurnBoundary() {
        val notice = message("bgproc_exit", Participant.USER).copy(
            text = "【系统提示】\nprocess: 后台进程已结束\n\noutput:\ndump",
        )
        assertTrue(MessageGenerationBoundaryResolver.isRealUser(notice))
        val turns = buildMessageListTurns(
            listOf(
                message("user", Participant.USER),
                message("assistant", Participant.MODEL),
                notice,
                message("follow-up", Participant.MODEL),
            ),
        )
        assertEquals(2, turns.size)
        assertEquals(listOf("user", "assistant"), turns[0].messages.map { it.id })
        assertEquals(listOf("bgproc_exit", "follow-up"), turns[1].messages.map { it.id })
    }

    @Test
    fun thoughtDetailChunksStayPrefixStable() {
        val prefix = buildString { repeat(80) { append("段落$it\n") } }
        val longer = prefix + "续写更多思考"
        val prefixChunks = thoughtDetailChunks(prefix, chunkCodePoints = 40)
        val longerChunks = thoughtDetailChunks(longer, chunkCodePoints = 40)
        assertEquals(
            prefixChunks.dropLast(1),
            longerChunks.take((prefixChunks.size - 1).coerceAtLeast(0)),
        )
        assertEquals(listOf("短"), thoughtDetailChunks("短", 40))
        assertTrue(thoughtDetailChunks("", 40).isEmpty())
        val first = thoughtDetailChunks(prefix, chunkCodePoints = 40).first()
        assertEquals(first, thoughtDetailChunks(prefix + "TAIL", chunkCodePoints = 40).first())
    }

    @Test
    fun thoughtDetailStaysOffMarkdownLoaders() {
        data class Case(
            val name: String,
            val type: String,
            val streaming: Boolean,
            val footer: Boolean,
            val virtualized: Boolean,
            val plainThought: Boolean,
            val chunkedThought: Boolean = false,
            val hasDirectMarkdown: Boolean = false,
        )
        listOf(
            Case(
                "historical thought is plain",
                "thought",
                streaming = false,
                footer = false,
                virtualized = false,
                plainThought = true,
                chunkedThought = true,
            ),
            Case(
                "streaming thought is plain",
                "thought",
                streaming = true,
                footer = false,
                virtualized = false,
                plainThought = true,
                chunkedThought = true,
            ),
            Case(
                "compact preview stays markdown",
                "thought",
                streaming = false,
                footer = false,
                virtualized = false,
                plainThought = false,
                hasDirectMarkdown = true,
            ),
            Case(
                "historical transcription can virtualize",
                "transcription",
                streaming = false,
                footer = false,
                virtualized = true,
                plainThought = false,
            ),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.virtualized,
                usesVirtualizedSegmentDetail(
                    selectedSegmentCount = 1,
                    segmentType = case.type,
                    segmentContentIsBlank = false,
                    isStreaming = case.streaming,
                    hasFooter = case.footer,
                ),
            )
            assertEquals(
                case.name,
                case.plainThought,
                usesPlainThoughtDetail(case.type, case.hasDirectMarkdown),
            )
            assertEquals(
                case.name,
                case.chunkedThought,
                usesChunkedThoughtDetail(1, case.type, case.hasDirectMarkdown),
            )
        }
    }

    @Test
    fun compactUsesItsGraphPositionImmediatelyAfterThePrecedingMessage() {
        val renderedIds = buildMessageListTurns(
            listOf(
                message("user", Participant.USER),
                message("assistant", Participant.MODEL),
                message("compact_boundary", Participant.MODEL).copy(text = ""),
                message("later-assistant", Participant.MODEL),
            ),
        ).flatMap { it.messages }.map { it.id }

        assertEquals(
            listOf("user", "assistant", "compact_boundary", "later-assistant"),
            renderedIds,
        )
    }

    @Test
    fun contextProgressUsesFixedNinetyPercentThreshold() {
        assertFalse(contextUsageExceedsCompactThreshold(89, 100))
        assertFalse(contextUsageExceedsCompactThreshold(90, 100))
        assertTrue(contextUsageExceedsCompactThreshold(91, 100))
        assertFalse(contextUsageExceedsCompactThreshold(1, 0))
    }

    @Test
    fun contextProgressUsesTheSameCapacityThresholdInBothPresentations() {
        assertFalse(contextUsageAtCapacity(99, 100))
        assertTrue(contextUsageAtCapacity(100, 100))
        assertTrue(contextUsageAtCapacity(101, 100))
        assertFalse(contextUsageAtCapacity(1, 0))
    }

    @Test
    fun bottomSheetBackDismissesTheListAndReturnsDetailsToTheList() {
        assertEquals(
            SegmentSheetBackAction.DISMISS,
            segmentSheetBackAction(true, true, detailPageIndex = -1),
        )
        assertEquals(
            SegmentSheetBackAction.SHOW_LIST,
            segmentSheetBackAction(true, true, detailPageIndex = 0),
        )
        assertEquals(
            SegmentSheetBackAction.DISMISS,
            segmentSheetBackAction(false, true, detailPageIndex = 0),
        )
    }

    @Test
    fun compactActionsAreDisabledForEveryBusyConversationState() {
        assertTrue(compactMessageActionsEnabled(false, false, false))
        assertFalse(compactMessageActionsEnabled(true, false, false))
        assertFalse(compactMessageActionsEnabled(false, true, false))
        assertFalse(compactMessageActionsEnabled(false, false, true))
    }

    @Test
    fun newlyCreatedCompactUsesTheSharedOneShotEntrance() {
        val compact = message("compact_boundary", Participant.MODEL).copy(
            status = MessageStatus.SENDING,
        )

        assertTrue(
            shouldAnimateMessageLifecycleEntrance(
                message = compact,
                isKnown = false,
                isLoading = true,
                isStreaming = true,
                lastUserMessageId = null,
                requestedTargetMessageId = null,
            ),
        )
        assertFalse(
            shouldAnimateMessageLifecycleEntrance(
                message = compact,
                isKnown = true,
                isLoading = true,
                isStreaming = true,
                lastUserMessageId = null,
                requestedTargetMessageId = null,
            ),
        )
    }

    @Test
    fun compactGenerationDoesNotOwnTheOrdinaryAssistantStreamingTail() {
        val compact = message("compact_boundary", Participant.MODEL).copy(
            status = MessageStatus.SENDING,
        )
        val assistant = message("assistant", Participant.MODEL).copy(
            status = MessageStatus.SENDING,
        )

        assertFalse(
            shouldShowStreamingTailIndicator(
                isLoading = true,
                isStopping = false,
                message = compact,
            ),
        )
        assertTrue(
            shouldShowStreamingTailIndicator(
                isLoading = true,
                isStopping = false,
                message = assistant,
            ),
        )
    }

    @Test
    fun stoppedCompactUsesDedicatedStoppedPillPresentation() {
        assertEquals(
            ContextCompactPillPresentation.STOPPED,
            contextCompactPillPresentation(MessageStatus.STOPPED),
        )
    }

    private fun message(id: String, participant: Participant) = ChatMessage(
        id = id,
        text = id,
        participant = participant,
    )
}
