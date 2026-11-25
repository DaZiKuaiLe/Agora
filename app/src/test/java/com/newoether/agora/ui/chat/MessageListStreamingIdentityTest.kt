package com.newoether.agora.ui.chat

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class MessageListStreamingIdentityTest {
    @Test
    fun turnCache_replacesCopiedHistoryInsteadOfKeepingStaleText() {
        val cache = MessageListTurnCache()
        val user1 = message("user-1", Participant.USER)
        val assistant1 = message("assistant-1", Participant.MODEL)
        val user2 = message("user-2", Participant.USER)
        val stream = message("assistant-2", Participant.MODEL).copy(text = "ab")
        val before = cache.update(listOf(user1, assistant1, user2, stream))

        val edited = assistant1.copy(text = "edited")
        val copiedHistory = cache.update(listOf(user1, edited, user2, stream))
        val streamedAgain = cache.update(
            listOf(user1, edited, user2, stream.copy(text = "abc")),
        )

        assertNotSame(before.first(), copiedHistory.first())
        assertEquals("edited", copiedHistory.first().messages.last().text)
        assertSame(copiedHistory.first(), streamedAgain.first())
        assertNotSame(copiedHistory.last(), streamedAgain.last())
    }

    @Test
    fun turnCountCache_matchesTurnBoundariesAndIgnoresMessageBodies() {
        val twoTurns = listOf(
            message("user-1", Participant.USER),
            message("assistant-1", Participant.MODEL),
            message("user-2", Participant.USER),
            message("assistant-2", Participant.MODEL),
        )
        data class Case(val name: String, val messages: List<ChatMessage>)
        val cases = listOf(
            Case("two turns", twoTurns),
            Case(
                "streamed body",
                twoTurns.map { item ->
                    if (item.id == "assistant-2") item.copy(text = "longer", thoughts = "thinking") else item
                },
            ),
            Case("new user turn", twoTurns + message("user-3", Participant.USER)),
            Case("deleted suffix", twoTurns.dropLast(2)),
            Case(
                "shorter branch",
                listOf(
                    message("other-user", Participant.USER),
                    message("other-assistant", Participant.MODEL),
                ),
            ),
            Case(
                "leading error",
                listOf(message("error-1", Participant.ERROR)) + twoTurns,
            ),
        )
        val cache = MessageListTurnCountCache()
        for (case in cases) {
            assertEquals(
                case.name,
                buildMessageListTurns(case.messages).size,
                cache.countOf(case.messages),
            )
        }
    }

    @Test
    fun sameRunProjectionIdentity_ignoresTextAndSeesRunIdentity() {
        val user = message("user-1", Participant.USER)
        val assistant = message("assistant-1", Participant.MODEL).copy(
            runId = "run-1",
            runSequence = 2L,
        )
        val path = listOf(user, assistant)
        data class Case(val name: String, val next: List<ChatMessage>, val same: Boolean)
        val cases = listOf(
            Case(
                "streamed text",
                listOf(user, assistant.copy(text = "longer", thoughts = "thinking")),
                true,
            ),
            Case("new run", listOf(user, assistant.copy(runId = "run-2")), false),
            Case(
                "replaced assistant",
                listOf(user, message("assistant-2", Participant.MODEL)),
                false,
            ),
        )
        for (case in cases) {
            assertEquals(case.name, case.same, sameRunProjectionIdentity(path, case.next))
        }
    }

    private fun message(id: String, participant: Participant) = ChatMessage(
        id = id,
        text = id,
        participant = participant,
    )
}
