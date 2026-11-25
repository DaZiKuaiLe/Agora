package com.newoether.agora.api.util

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import org.junit.Assert.assertEquals
import org.junit.Test

class WireTurnCapTest {
    @Test
    fun omitsSuppressedDialogueTurns() {
        val messages = buildList {
            add(user("context_digest_1", "digest"))
            repeat(2) { index ->
                val n = index + 1
                add(user("u$n", "ask $n"))
                add(tool("tool_$n"))
                add(result("result_$n"))
                add(assistant("a$n"))
            }
        }
        val cases = listOf(
            emptySet<String>() to listOf("context_digest_1", "u1", "tool_1", "result_1", "a1", "u2", "tool_2", "result_2", "a2"),
            setOf("u2") to listOf("context_digest_1", "u1", "tool_1", "result_1", "a1"),
            setOf("a1") to listOf("context_digest_1", "u1", "tool_1", "result_1", "u2", "tool_2", "result_2", "a2"),
            setOf("context_digest_1") to listOf("u1", "tool_1", "result_1", "a1", "u2", "tool_2", "result_2", "a2"),
            setOf("tool_2") to listOf("context_digest_1", "u1", "tool_1", "result_1", "a1", "u2", "a2"),
            setOf("missing") to listOf("context_digest_1", "u1", "tool_1", "result_1", "a1", "u2", "tool_2", "result_2", "a2"),
        )
        cases.forEach { (suppressed, expectedIds) ->
            val kept = omitSuppressedDialogueTurns(messages, suppressed)
            assertEquals(expectedIds, kept.map { it.id })
            val muted = mutedWireMessageIds(messages, suppressed)
            if ("u2" in suppressed) {
                assertEquals(true, muted.containsAll(listOf("u2", "tool_2", "result_2", "a2")))
            }
            if ("tool_2" in suppressed) {
                assertEquals(true, "result_2" in muted && "tool_2" in muted && "a2" !in muted)
            }
        }
    }

    @Test
    fun restoresDebugWireCapText() {
        val cases = listOf(
            "" to emptySet(),
            "0" to emptySet<String>(),
            "4\nu2\ntool_1" to setOf("u2", "tool_1"),
            "nope\nid" to setOf("nope", "id"),
            "4\nnotices=0\nu2" to setOf("u2"),
            "8\nnotices=1\nu2" to setOf("u2"),
            "u2\ntool_1" to setOf("u2", "tool_1"),
        )
        cases.forEach { (text, expected) ->
            assertEquals(expected, decodeDebugWireCap(text))
        }
        assertEquals("a\nb", encodeDebugWireCap(setOf("a", "b", "a")))
    }

    private fun user(id: String, text: String) = ChatMessage(
        id = id,
        text = text,
        participant = Participant.USER,
        status = MessageStatus.SUCCESS,
    )

    private fun assistant(id: String) = ChatMessage(
        id = id,
        text = "answer",
        participant = Participant.MODEL,
        status = MessageStatus.SUCCESS,
    )

    private fun tool(id: String) = ChatMessage(
        id = id,
        text = "",
        participant = Participant.MODEL,
        status = MessageStatus.SUCCESS,
    )

    private fun result(id: String) = ChatMessage(
        id = id,
        text = "result",
        participant = Participant.USER,
        status = MessageStatus.SUCCESS,
    )
}
