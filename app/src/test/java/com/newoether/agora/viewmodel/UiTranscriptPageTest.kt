package com.newoether.agora.viewmodel

import com.newoether.agora.data.local.UiMessageGraphRow
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import org.junit.Assert.assertEquals
import org.junit.Test

class UiTranscriptPageTest {
    @Test
    fun transcriptPage_keepsTurnsWholeAndOpensOnTheTail() {
        data class Case(
            val name: String,
            val chain: List<UiMessageGraphRow>,
            val end: Int?,
            val ids: List<String>,
            val earlier: Boolean,
            val last: Boolean,
            val start: Int,
        )
        val six = dialogue(6)
        val withTool = listOf(
            row("u", null, Participant.USER, 1),
            row("tool_1", "u", Participant.MODEL, 2),
            row("m", "tool_1", Participant.MODEL, 3),
        )
        val leading = listOf(
            row("m0", null, Participant.MODEL, 1),
            row("u1", "m0", Participant.USER, 2),
            row("m1", "u1", Participant.MODEL, 3),
        )
        val cases = listOf(
            Case("empty", emptyList(), null, emptyList(), false, true, 0),
            Case("three", dialogue(3), null, dialogue(3).map { it.id }, false, true, 0),
            Case("five", dialogue(5), null, dialogue(5).map { it.id }, false, true, 0),
            Case(
                "last of six",
                six,
                null,
                six.drop(2).map { it.id },
                true,
                true,
                1,
            ),
            Case(
                "first of six",
                six,
                1,
                six.take(2).map { it.id },
                false,
                false,
                0,
            ),
            Case("tool stays in the turn", withTool, null, listOf("u", "tool_1", "m"), false, true, 0),
            Case("leading model joins the first turn", leading, null, listOf("m0", "u1", "m1"), false, true, 0),
        )
        for (case in cases) {
            val page = transcriptPage(case.chain, case.end)
            assertEquals(case.name, case.ids, page.chain.map { it.id })
            assertEquals(case.name, case.earlier, page.hasEarlier)
            assertEquals(case.name, case.last, page.isLastPage)
            assertEquals(case.name, case.start, page.startTurnExclusive)
        }
    }

    @Test
    fun selectedTranscriptChain_followsTheSelectedSibling() {
        val rows = listOf(
            row("u1", null, Participant.USER, 1),
            row("u2", null, Participant.USER, 2),
            row("m1", "u1", Participant.MODEL, 3),
            row("m2", "u2", Participant.MODEL, 4),
        )
        data class Case(val name: String, val selected: Map<String?, String>, val ids: List<String>)
        val cases = listOf(
            Case("older edit", mapOf(null to "u1"), listOf("u1", "m1")),
            Case("latest when unset", emptyMap(), listOf("u2", "m2")),
        )
        for (case in cases) {
            assertEquals(
                case.name,
                case.ids,
                selectedTranscriptChain(rows, case.selected).map { it.id },
            )
        }
    }

    @Test
    fun resolvePath_startsAtTheLoadedPageWhenTheRootIsAbsent() {
        val page = listOf(
            ChatMessage(id = "m2", parentId = "m1", text = "a", participant = Participant.USER, timestamp = 2),
            ChatMessage(id = "m3", parentId = "m2", text = "b", participant = Participant.MODEL, timestamp = 3),
        )
        val rooted = listOf(
            ChatMessage(id = "m1", parentId = null, text = "root", participant = Participant.USER, timestamp = 1),
        ) + page
        data class Case(val name: String, val messages: List<ChatMessage>, val ids: List<String>)
        val cases = listOf(
            Case("page without root", page, listOf("m2", "m3")),
            Case("full chain still starts at the root", rooted, listOf("m1", "m2", "m3")),
        )
        for (case in cases) {
            assertEquals(
                case.name,
                case.ids,
                ConversationUiState.resolvePath(case.messages, null, emptyMap()).map { it.id },
            )
        }
    }

    @Test
    fun advanceTranscriptTokenTotal_seatsOnceThenAppliesDeltas() {
        data class Case(
            val name: String,
            val seated: Int?,
            val previous: Map<String, Int>?,
            val next: Map<String, Int>,
            val total: Int,
        )
        val first = mapOf("a" to 10, "b" to 5)
        val grown = first + ("c" to 4)
        val cases = listOf(
            Case("seat from counts", null, null, first, 15),
            Case("trust the stored total", 15, null, first, 15),
            Case("add a message", 15, first, grown, 19),
            Case("drop a message", 19, grown, first, 15),
            Case("ignore tool rows", null, null, countedTranscriptTokens(
                listOf(row("a", null, Participant.USER, 1, tokens = 10), row("tool_x", "a", Participant.MODEL, 2, tokens = 99)),
            ), 10),
        )
        for (case in cases) {
            val (total, applied) = advanceTranscriptTokenTotal(case.seated, case.previous, case.next)
            assertEquals(case.name, case.total, total)
            assertEquals(case.name, case.next, applied)
        }
    }

    private fun dialogue(turns: Int): List<UiMessageGraphRow> {
        val rows = ArrayList<UiMessageGraphRow>(turns * 2)
        var parent: String? = null
        for (index in 1..turns) {
            val user = "u$index"
            val model = "m$index"
            rows += row(user, parent, Participant.USER, index * 2L)
            rows += row(model, user, Participant.MODEL, index * 2L + 1)
            parent = model
        }
        return rows
    }

    private fun row(
        id: String,
        parentId: String?,
        participant: Participant,
        timestamp: Long,
        tokens: Int = 1,
    ) = UiMessageGraphRow(
        id = id,
        parentId = parentId,
        status = MessageStatus.SUCCESS,
        compactDigestRev = 0,
        compactBatchId = null,
        turnId = id,
        participant = participant,
        timestamp = timestamp,
        runId = "run",
        tokenCount = tokens,
    )
}
