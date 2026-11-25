package com.newoether.agora.viewmodel

import com.newoether.agora.api.util.contextWindowUsage
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.tool.CompactTurnIndexer
import org.junit.Assert.assertEquals
import org.junit.Test

class ReplayAutomaticCompactSlicesTest {
    @Test
    fun replayAutomaticCompactSlices_matchesLiveCadenceAndLeavesUnderThresholdTail() {
        data class Case(
            val name: String,
            val turns: List<Pair<ChatMessage, ChatMessage>>,
            val retainTurns: Int,
            val thresholdOf: List<Pair<ChatMessage, ChatMessage>>?,
            val extraThreshold: Int = 0,
            val expectedSliceTurnIds: List<List<String>>,
        )
        val prefixTwo = listOf(
            turn("old", text = "old context ".repeat(80)),
            turn("mid", text = "mid context ".repeat(80)),
        )
        val firstThree = prefixTwo + listOf(
            turn("new", text = "new request"),
        )
        val withTail = firstThree + listOf(
            turn("tail_a", text = "tail a"),
            turn("tail_b", text = "tail b"),
        )
        val tinyThenHugeRetain = listOf(
            turn("tiny", text = "x"),
            turn("huge", text = "retain window ".repeat(200)),
        )
        val cases = listOf(
            Case(
                name = "under threshold yields no slices",
                turns = firstThree,
                retainTurns = 1,
                thresholdOf = prefixTwo,
                extraThreshold = 1,
                expectedSliceTurnIds = emptyList(),
            ),
            Case(
                name = "first crossing folds prefix minus retain",
                turns = firstThree,
                retainTurns = 1,
                thresholdOf = prefixTwo,
                expectedSliceTurnIds = listOf(listOf("turn_old", "turn_mid")),
            ),
            Case(
                name = "later turns under threshold stay original",
                turns = withTail,
                retainTurns = 1,
                thresholdOf = prefixTwo,
                expectedSliceTurnIds = listOf(listOf("turn_old", "turn_mid")),
            ),
            Case(
                name = "retain tokens alone do not create slices",
                turns = tinyThenHugeRetain,
                retainTurns = 1,
                thresholdOf = tinyThenHugeRetain,
                expectedSliceTurnIds = emptyList(),
            ),
        )
        for (case in cases) {
            val path = case.turns.flatMap { listOf(it.first, it.second) }
            val threshold = if (case.thresholdOf == null) {
                1
            } else {
                usage(case.thresholdOf.flatMap { listOf(it.first, it.second) }) + case.extraThreshold
            }
            val slices = replayAutomaticCompactSlices(
                turns = CompactTurnIndexer.finishedDialogueTurns(path),
                retainTurns = case.retainTurns,
                compactThreshold = threshold,
            )
            assertEquals(
                case.name,
                case.expectedSliceTurnIds,
                slices.map { slice -> slice.map { it.turnId } },
            )
            val indexedTurns = CompactTurnIndexer.finishedDialogueTurns(path)
            // Marginal cost inside the growing prefix. A standalone turn projection repeats
            // the latest-user work-mode line, so summing those figures crosses one token early.
            val tokensByTurn = IntArray(indexedTurns.size)
            var seen = 0
            for (index in indexedTurns.indices) {
                val total = usage(indexedTurns.take(index + 1).flatMap { it.messages })
                tokensByTurn[index] = total - seen
                seen = total
            }
            val sliceIndices = replayAutomaticCompactSliceIndices(
                turnCount = indexedTurns.size,
                retainTurns = case.retainTurns,
                compactThreshold = threshold,
                tokensByTurn = tokensByTurn,
            )
            assertEquals(
                case.name,
                case.expectedSliceTurnIds,
                sliceIndices.map { indices -> indices.map { indexedTurns[it].turnId } },
            )
        }
        val tailPath = withTail.flatMap { listOf(it.first, it.second) }
        val tailSlices = replayAutomaticCompactSlices(
            turns = CompactTurnIndexer.finishedDialogueTurns(tailPath),
            retainTurns = 1,
            compactThreshold = usage(prefixTwo.flatMap { listOf(it.first, it.second) }),
        )
        val compactedIds = tailSlices.flatten().map { it.turnId }.toSet()
        assertEquals(setOf("turn_old", "turn_mid"), compactedIds)
        val leftoverPrefix = CompactTurnIndexer.finishedDialogueTurns(tailPath)
            .dropLast(1)
            .map { it.turnId }
            .filterNot { it in compactedIds }
        assertEquals(listOf("turn_new", "turn_tail_a"), leftoverPrefix)
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

    private fun turn(
        id: String,
        text: String,
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
        )
        return user to assistant
    }
}
