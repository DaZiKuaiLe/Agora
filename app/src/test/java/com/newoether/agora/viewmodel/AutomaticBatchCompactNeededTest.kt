package com.newoether.agora.viewmodel

import com.newoether.agora.api.util.contextWindowUsage
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticBatchCompactNeededTest {
    @Test
    fun skipsWhenRetainWindowLeavesNothingToCompact() {
        val path = pathOf(turn("only", text = "huge ".repeat(400)))
        assertFalse(
            automaticBatchCompactNeeded(path = path, contextLimit = 1, retainTurns = 1),
        )
    }

    @Test
    fun firstCompactUsesUncompressedOriginals() {
        val path = pathOf(
            turn("old", text = "old context ".repeat(80)),
            turn("mid", text = "mid context ".repeat(80)),
            turn("new", text = "new request"),
        )
        val uncompressed = usage(uncompressedCompactThresholdMessages(path, retainTurns = 1))
        assertTrue(
            automaticBatchCompactNeeded(
                path = path,
                contextLimit = uncompressed,
                retainTurns = 1,
            ),
        )
        assertFalse(
            automaticBatchCompactNeeded(
                path = path,
                contextLimit = uncompressed + 1,
                retainTurns = 1,
            ),
        )
    }

    @Test
    fun batchedOriginalsAndSummariesDoNotCountTowardThreshold() {
        val path = pathOf(
            turn(
                "old",
                text = "already compacted original ".repeat(200),
                batchId = "batch_x",
                summary = "tiny summary",
            ),
            turn("a", text = "fresh a"),
            turn("b", text = "fresh b"),
        )
        val uncompressed = usage(uncompressedCompactThresholdMessages(path, retainTurns = 1))
        val includingCompressed = usage(path)
        assertTrue(includingCompressed > uncompressed + 50)

        assertFalse(
            automaticBatchCompactNeeded(
                path = path,
                contextLimit = uncompressed + 1,
                retainTurns = 1,
            ),
        )
        assertTrue(
            automaticBatchCompactNeeded(
                path = path,
                contextLimit = uncompressed,
                retainTurns = 1,
            ),
        )
    }

    @Test
    fun uncompressedGrowthRetriggersAfterEarlierBatches() {
        val path = pathOf(
            turn("old", text = "old", batchId = "batch_x", summary = "sum"),
            turn("a", text = "growing uncompressed ".repeat(100)),
            turn("b", text = "more uncompressed ".repeat(100)),
        )
        val uncompressed = usage(uncompressedCompactThresholdMessages(path, retainTurns = 1))
        assertTrue(
            automaticBatchCompactNeeded(
                path = path,
                contextLimit = uncompressed,
                retainTurns = 1,
            ),
        )
    }

    @Test
    fun lockedUnbatchedPrefixStillCountsAsUncompressedGrowth() {
        val path = pathOf(
            turn("old", text = "locked but not covered ".repeat(80), locked = true),
            turn("keep", text = "recent"),
        )
        val uncompressed = usage(uncompressedCompactThresholdMessages(path, retainTurns = 1))
        assertTrue(
            automaticBatchCompactNeeded(
                path = path,
                contextLimit = uncompressed,
                retainTurns = 1,
            ),
        )
    }

    @Test
    fun alreadyBatchedPrefixDoesNotRetriggerJustBecauseSummariesExist() {
        val path = pathOf(
            turn("old", text = "old ".repeat(200), batchId = "batch_x", summary = "sum ".repeat(80)),
            turn("keep", text = "recent"),
        )
        assertFalse(
            automaticBatchCompactNeeded(
                path = path,
                contextLimit = 1,
                retainTurns = 1,
            ),
        )
    }

    @Test
    fun retainWindowTokensDoNotCountTowardThreshold() {
        val path = pathOf(
            turn("tiny", text = "x"),
            turn("keep", text = "retain window ".repeat(200)),
        )
        val prefixOnly = usage(uncompressedCompactThresholdMessages(path, retainTurns = 1))
        val includingRetain = usage(path)
        assertTrue(includingRetain > prefixOnly + 50)
        assertFalse(
            automaticBatchCompactNeeded(
                path = path,
                contextLimit = includingRetain,
                retainTurns = 1,
            ),
        )
        assertTrue(
            automaticBatchCompactNeeded(
                path = path,
                contextLimit = prefixOnly,
                retainTurns = 1,
            ),
        )
    }

    private fun usage(messages: List<ChatMessage>): Int =
        contextWindowUsage(
            projectGenerationInputMessages(
                messages = messages,
                includeImages = true,
                userPrepend = null,
                userPostpend = null,
            ),
            tokenBudget = Int.MAX_VALUE,
        ).estimatedTokenCount

    private fun pathOf(vararg turns: Pair<ChatMessage, ChatMessage>): List<ChatMessage> =
        turns.flatMap { listOf(it.first, it.second) }

    private fun turn(
        id: String,
        text: String,
        batchId: String? = null,
        summary: String? = null,
        locked: Boolean = false,
    ): Pair<ChatMessage, ChatMessage> {
        val user = ChatMessage(
            id = "u_$id",
            text = "user $id $text",
            participant = Participant.USER,
            status = MessageStatus.SUCCESS,
        )
        val assistant = ChatMessage(
            id = id,
            text = "assistant $id $text",
            participant = Participant.MODEL,
            status = MessageStatus.SUCCESS,
            parentId = user.id,
            turnId = "turn_$id",
            compactLocked = locked,
            compactBatchId = batchId,
            compactSummary = summary,
        )
        return user to assistant
    }
}
