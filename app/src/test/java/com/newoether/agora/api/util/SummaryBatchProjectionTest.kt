package com.newoether.agora.api.util

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryBatchProjectionTest {
    @Test
    fun projectsConsecutiveBatchesAsOneJsonPayload() {
        val t1 = turn("a1", "turn_1", batchId = "batch_x", summary = "Sum A")
        val t2 = turn("a2", "turn_2", batchId = "batch_x", summary = "Sum A")
        val t3 = turn("a3", "turn_3", batchId = "batch_y", summary = "Sum B")
        val t4 = turn("a4", "turn_4", batchId = null, summary = null)
        val projected = applySummaryBatchProjection(
            listOf(
                t1.first, t1.second,
                t2.first, t2.second,
                t3.first, t3.second,
                t4.first, t4.second,
            ),
            retainTurns = 0,
        )
        assertEquals(1, projected.count { it.id.startsWith("context_batch_") })
        val jsonMsg = projected.first { it.id == "context_batch_batch_x" }
        assertTrue(jsonMsg.text.contains("\"type\": \"summary_batches\""))
        assertTrue(jsonMsg.text.contains("\"batchId\": \"batch_x\""))
        assertTrue(jsonMsg.text.contains("\"batchId\": \"batch_y\""))
        assertTrue(jsonMsg.text.contains("Sum A"))
        assertTrue(jsonMsg.text.contains("Sum B"))
        assertTrue(projected.any { it.id == t4.second.id })
    }

    @Test
    fun retainWindowKeepsOriginalsEvenIfBatched() {
        val older = turn("a1", "turn_1", batchId = "batch_x", summary = "Old")
        val recent = turn("a2", "turn_2", batchId = "batch_y", summary = "New")
        val projected = applySummaryBatchProjection(
            listOf(older.first, older.second, recent.first, recent.second),
            retainTurns = 1,
        )
        assertTrue(projected.none { it.id == "context_batch_batch_y" })
        assertTrue(projected.any { it.id == recent.second.id })
        assertTrue(projected.any { it.id == "context_batch_batch_x" })
    }

    @Test
    fun lockedTurnFlushesPendingBatchesBeforeOriginals() {
        val b1 = turn("a1", "turn_1", batchId = "batch_x", summary = "A")
        val locked = turn("a2", "turn_2", batchId = null, summary = null, locked = true)
        val b2 = turn("a3", "turn_3", batchId = "batch_y", summary = "B")
        val projected = applySummaryBatchProjection(
            listOf(b1.first, b1.second, locked.first, locked.second, b2.first, b2.second),
            retainTurns = 0,
        )
        assertEquals(2, projected.count { it.id.startsWith("context_batch_") })
        assertTrue(projected.any { it.id == locked.second.id })
    }

    @Test
    fun readingFormatRendersSeparators() {
        val json = encodeSummaryBatchesJson(
            listOf(
                SummaryBatchItem("batch_1", "First"),
                SummaryBatchItem("batch_2", "Second"),
            ),
        )
        val reading = formatSummaryBatchesForReading(json)
        assertNotNull(reading)
        assertTrue(reading!!.contains("======== 批次 1 / batchId=batch_1 ========"))
        assertTrue(reading.contains("======== 批次 2 / batchId=batch_2 ========"))
        assertTrue(reading.contains("First"))
        assertTrue(reading.contains("Second"))
        assertFalse(reading.contains("\"type\""))
    }

    @Test
    fun keepsTrailingInFlightUserAfterUnbatchedTailTurns() {
        // After full recompact, the retain window stays unbatched; the new send is user-only
        // (no assistant yet) and must remain the history terminator for OpenAI/DeepSeek.
        val batched = turn("a1", "turn_1", batchId = "batch_x", summary = "Old")
        val retain = turn("a2", "turn_2", batchId = null, summary = null)
        val inFlight = ChatMessage(
            id = "u_new",
            text = "fresh question",
            participant = Participant.USER,
            status = MessageStatus.SUCCESS,
            parentId = retain.second.id,
        )
        val projected = applySummaryBatchProjection(
            listOf(batched.first, batched.second, retain.first, retain.second, inFlight),
            retainTurns = 0,
        )
        assertEquals(inFlight.id, projected.last().id)
        assertEquals(Participant.USER, projected.last().participant)
        assertEquals("fresh question", projected.last().text)
        assertTrue(projected.any { it.id == "context_batch_batch_x" })
        assertTrue(projected.any { it.id == retain.second.id })
    }

    @Test
    fun keepsTrailingInFlightUserWhenAllPriorTurnsAreBatched() {
        val t1 = turn("a1", "turn_1", batchId = "batch_x", summary = "A")
        val t2 = turn("a2", "turn_2", batchId = "batch_y", summary = "B")
        val inFlight = ChatMessage(
            id = "u_new",
            text = "next",
            participant = Participant.USER,
            status = MessageStatus.SUCCESS,
            parentId = t2.second.id,
        )
        val projected = applySummaryBatchProjection(
            listOf(t1.first, t1.second, t2.first, t2.second, inFlight),
            retainTurns = 0,
        )
        assertEquals(listOf("context_batch_batch_x", "u_new"), projected.map { it.id })
    }

    private fun turn(
        assistantId: String,
        turnId: String,
        batchId: String?,
        summary: String?,
        locked: Boolean = false,
    ): Pair<ChatMessage, ChatMessage> {
        val user = ChatMessage(
            id = "u_$assistantId",
            text = "user $assistantId",
            participant = Participant.USER,
            status = MessageStatus.SUCCESS,
        )
        val assistant = ChatMessage(
            id = assistantId,
            text = "assistant $assistantId",
            participant = Participant.MODEL,
            status = MessageStatus.SUCCESS,
            parentId = user.id,
            turnId = turnId,
            compactLocked = locked,
            compactBatchId = batchId,
            compactSummary = summary,
        )
        return user to assistant
    }
}
