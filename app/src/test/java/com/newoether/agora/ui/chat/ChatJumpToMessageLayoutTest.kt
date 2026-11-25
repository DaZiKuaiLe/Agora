package com.newoether.agora.ui.chat

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatJumpToMessageLayoutTest {
    @Test
    fun resolveJumpTurnIndexFindsTheTurnContainingTheMessage() {
        val messages = listOf(
            message("user-1", Participant.USER),
            message("assistant-1", Participant.MODEL),
            message("user-2", Participant.USER),
            message("assistant-2", Participant.MODEL),
        )
        assertEquals(0, resolveJumpTurnIndex("user-1", messages))
        assertEquals(0, resolveJumpTurnIndex("assistant-1", messages))
        assertEquals(1, resolveJumpTurnIndex("user-2", messages))
        assertEquals(-1, resolveJumpTurnIndex("missing", messages))
        assertEquals(-1, resolveJumpTurnIndex("  ", messages))
    }

    @Test
    fun messageStartOffsetInTurnIsPrecedingHeightsOnly() {
        val turn = buildMessageListTurns(
            listOf(
                message("user-1", Participant.USER),
                message("assistant-1", Participant.MODEL),
                message("error-1", Participant.ERROR),
            ),
        ).single()
        val heights = mapOf("user-1" to 100, "assistant-1" to 250, "error-1" to 80)

        assertEquals(
            0f,
            estimateMessageStartInTurnPx(turn, "user-1", heights, fallbackHeightPx = 72f),
            0f,
        )
        assertEquals(
            100f,
            estimateMessageStartInTurnPx(turn, "assistant-1", heights, fallbackHeightPx = 72f),
            0f,
        )
        assertEquals(
            350f,
            estimateMessageStartInTurnPx(turn, "error-1", heights, fallbackHeightPx = 72f),
            0f,
        )
    }

    private fun message(id: String, participant: Participant): ChatMessage =
        ChatMessage(id = id, text = id, participant = participant)
}
