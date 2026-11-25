package com.newoether.agora.viewmodel

import com.newoether.agora.data.local.UiMessageGraphRow
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class UiMessageIncrementalProjectionTest {
    @Test
    fun fetchAndAssemble_onlyTouchChangedGraphRows() {
        val old = message("a1", MessageStatus.SUCCESS, "t1")
        val live = message("live", MessageStatus.SENDING, null)
        val added = message("a2", MessageStatus.SUCCESS, "t2")
        val rows = listOf(
            row("a1", MessageStatus.SUCCESS, "t1"),
            row("live", MessageStatus.THINKING, null),
            row("a2", MessageStatus.SUCCESS, "t2"),
        )
        val previous = mapOf(
            "a1" to ProjectedUiMessage(row("a1", MessageStatus.SUCCESS, "t1").fingerprint(), old),
            "live" to ProjectedUiMessage(row("live", MessageStatus.SENDING, null).fingerprint(), live),
        )
        data class Case(
            val name: String,
            val graph: List<UiMessageGraphRow>,
            val streamingId: String?,
            val existing: Map<String, ChatMessage>,
            val expectedFetch: List<String>,
        )
        listOf(
            Case("stream owns live", rows, "live", mapOf("a1" to old), listOf("a2")),
            Case(
                "terminal already in memory",
                listOf(
                    row("a1", MessageStatus.SUCCESS, "t1"),
                    row("a2", MessageStatus.SUCCESS, "t2"),
                ),
                null,
                mapOf("a1" to old, "a2" to added),
                emptyList(),
            ),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expectedFetch,
                incrementalUiMessageIdsToFetch(
                    rows = case.graph,
                    previous = previous,
                    existingById = case.existing,
                    streamingMessageId = case.streamingId,
                ),
            )
        }

        val assembled = assembleIncrementalUiMessages(
            rows = rows,
            previous = previous,
            existingById = mapOf("a1" to old),
            decoded = mapOf("a2" to added),
            streamingMessageId = "live",
        )
        assertEquals(listOf("a1", "live", "a2"), assembled.first.map { it.id })
        assertSame(old, assembled.first[0])
        assertSame(live, assembled.first[1])
        assertSame(added, assembled.first[2])

        val sameRefs = listOf(old, live, added)
        assertSame(sameRefs, reuseProjectedMessageList(sameRefs, listOf(old, live, added)))
    }

    private fun message(id: String, status: MessageStatus, turnId: String?) = ChatMessage(
        id = id,
        text = id,
        participant = if (id.startsWith("a")) Participant.MODEL else Participant.USER,
        status = status,
        turnId = turnId,
    )

    private fun row(id: String, status: MessageStatus, turnId: String?) = UiMessageGraphRow(
        id = id,
        parentId = null,
        status = status,
        compactDigestRev = 0,
        compactBatchId = null,
        turnId = turnId,
    )
}
