package com.newoether.agora.viewmodel

import com.newoether.agora.data.local.CompactMessageGraphRow
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.tool.CompactTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactProviderPathTest {
    @Test
    fun graphStubsHaveNoBodiesAndStillAssembleToolRounds() {
        val graph = listOf(
            row("u", null, Participant.USER, 0),
            row("m", "u", Participant.MODEL, 1),
            row("tool_round", "m", Participant.MODEL, 2),
            row("result_round", "tool_round", Participant.USER, 3),
            row("queued", "result_round", Participant.USER, 4),
        )
        graph.forEach { row ->
            val stub = row.toStubMessageEntity()
            assertEquals("", stub.text)
            assertNull(stub.thoughts)
            assertNull(stub.toolCallJson)
            assertNull(stub.compactDigest)
        }
        val path = assembleCompactProviderPath(graph, selectedChildren = emptyMap())
        assertEquals(
            listOf("u", "tool_round", "result_round", "m", "queued"),
            path.map { it.id },
        )
        assertTrue(path.all { it.text.isEmpty() })
    }

    @Test
    fun newestDigestAnchorPrefersHighestRevWithStoredBody() {
        val path = listOf(
            ChatMessage(
                id = "u1",
                text = "",
                participant = Participant.USER,
                status = MessageStatus.SUCCESS,
            ),
            ChatMessage(
                id = "a1",
                text = "",
                participant = Participant.MODEL,
                status = MessageStatus.SUCCESS,
                parentId = "u1",
                turnId = "t1",
                compactBatchId = "summary",
                compactDigestRev = 1,
            ),
            ChatMessage(
                id = "u2",
                text = "",
                participant = Participant.USER,
                status = MessageStatus.SUCCESS,
                parentId = "a1",
            ),
            ChatMessage(
                id = "a2",
                text = "",
                participant = Participant.MODEL,
                status = MessageStatus.SUCCESS,
                parentId = "u2",
                turnId = "t2",
                compactBatchId = "summary",
                compactDigestRev = 2,
            ),
        )
        val chosen = newestBatchedDigestAnchorId(path) { id -> id == "a2" }
        assertEquals("a2" to 2, chosen)
        assertNull(newestBatchedDigestAnchorId(path) { false })
        val digest = pathDigestFromStored("chapter one", rev = 2)
        assertEquals(2, digest?.rev)
        assertEquals("chapter one", digest?.body)
        assertNull(pathDigestFromStored("  ", rev = 2))
    }

    @Test
    fun compactTurnsNeedBodyHydration_detectsEmptyStubsOnly() {
        val stubTurn = CompactTurn(
            turnId = "t1",
            locked = false,
            batchId = null,
            summary = null,
            messages = listOf(
                ChatMessage(
                    id = "u",
                    text = "",
                    participant = Participant.USER,
                    status = MessageStatus.SUCCESS,
                ),
            ),
            anchorId = "u",
        )
        val hydratedTurn = stubTurn.copy(
            messages = listOf(
                ChatMessage(
                    id = "u",
                    text = "hello",
                    participant = Participant.USER,
                    status = MessageStatus.SUCCESS,
                ),
            ),
        )
        assertTrue(compactTurnsNeedBodyHydration(listOf(stubTurn)))
        assertFalse(compactTurnsNeedBodyHydration(listOf(hydratedTurn)))
    }

    private fun row(
        id: String,
        parentId: String?,
        participant: Participant,
        sequence: Long,
    ) = CompactMessageGraphRow(
        id = id,
        conversationId = "conversation",
        parentId = parentId,
        participant = participant,
        status = MessageStatus.SUCCESS,
        timestamp = sequence,
        runId = "run",
        runSequence = sequence,
        modelName = "claude",
        consumedAtPass = null,
        turnId = null,
        compactLocked = false,
        compactBatchId = null,
        compactDigestRev = 0,
        hasCompactDigest = false,
    )
}
