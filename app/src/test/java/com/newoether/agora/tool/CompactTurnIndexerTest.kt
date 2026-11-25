package com.newoether.agora.tool

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import org.junit.Assert.assertEquals
import org.junit.Test

class CompactTurnIndexerTest {
    @Test
    fun finishedDialogueTurnsDropsLiveTailFromRetainAccounting() {
        val finishedUser = ChatMessage(
            id = "u1",
            text = "first",
            participant = Participant.USER,
            status = MessageStatus.SUCCESS,
        )
        val finishedAssistant = ChatMessage(
            id = "a1",
            text = "reply",
            participant = Participant.MODEL,
            status = MessageStatus.SUCCESS,
            parentId = finishedUser.id,
            turnId = "turn_1",
        )
        val liveUser = ChatMessage(
            id = "u2",
            text = "next question",
            participant = Participant.USER,
            status = MessageStatus.SUCCESS,
            parentId = finishedAssistant.id,
        )
        val path = listOf(finishedUser, finishedAssistant, liveUser)
        assertEquals(2, CompactTurnIndexer.indexDialogueTurns(path).size)
        val finished = CompactTurnIndexer.finishedDialogueTurns(path)
        assertEquals(1, finished.size)
        assertEquals("turn_1", finished.single().turnId)
    }
}
