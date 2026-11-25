package com.newoether.agora.api.util

import com.newoether.agora.data.TimeBandPolicy
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.tool.CompactTurnIndexer
import com.newoether.agora.util.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnIntegrationTest {
    @Test
    fun replacesOverflowTurnsWhenCacheHitAndKeepsRetainAndLocked() {
        val t1 = turn("a1", "turn_1", integrate = "T1 merged")
        val locked = turn("a2", "turn_2", locked = true, integrate = "should not show")
        val t3 = turn("a3", "turn_3", integrate = "T3 merged")
        val t4 = turn("a4", "turn_4", integrate = "T4 still original band")
        val live = ChatMessage(
            id = "u_live",
            text = "next question",
            participant = Participant.USER,
            status = MessageStatus.SUCCESS,
            parentId = t4.second.id,
        )
        val projected = applyIntegrationProjection(
            messages = listOf(
                t1.first, t1.second,
                locked.first, locked.second,
                t3.first, t3.second,
                t4.first, t4.second,
                live,
            ),
            retainTurns = 1,
        )
        assertTrue(projected.any { it.id.startsWith(TimeBandPolicy.TURN_INTEGRATION_ID_PREFIX) })
        assertEquals(1, projected.count { it.id.startsWith(TimeBandPolicy.TURN_INTEGRATION_ID_PREFIX) && it.text.contains("turn_1") })
        assertTrue(projected.any { it.id == "${TimeBandPolicy.TURN_INTEGRATION_ID_PREFIX}turn_1" })
        assertTrue(projected.any { it.id == "${TimeBandPolicy.TURN_INTEGRATION_ID_PREFIX}turn_3" })
        assertTrue(projected.any { it.id == locked.first.id })
        assertTrue(projected.any { it.id == t4.first.id })
        assertTrue(projected.any { it.id == live.id })
        val integrated = projected.first { it.id.endsWith("turn_1") }
        assertTrue(integrated.text.contains("\"type\": \"turn_integrations\""))
        assertTrue(integrated.text.contains("turn_1"))
        assertTrue(integrated.text.contains("T1 merged"))
        assertFalse(integrated.text.contains("T3 merged"))
    }

    @Test
    fun consecutiveOverflowTurnsShareOneJson() {
        val t1 = turn("a1", "turn_1", integrate = "T1")
        val t2 = turn("a2", "turn_2", integrate = "T2")
        val t3 = turn("a3", "turn_3", integrate = "keep original band")
        val projected = applyIntegrationProjection(
            messages = listOf(t1.first, t1.second, t2.first, t2.second, t3.first, t3.second),
            retainTurns = 1,
        )
        val bundles = projected.filter { it.id.startsWith(TimeBandPolicy.TURN_INTEGRATION_ID_PREFIX) }
        assertEquals(1, bundles.size)
        assertTrue(bundles.single().text.contains("\"type\": \"turn_integrations\""))
        assertTrue(bundles.single().text.contains("turn_1"))
        assertTrue(bundles.single().text.contains("turn_2"))
        assertTrue(projected.any { it.id == t3.first.id })
        assertFalse(projected.any { it.id == t1.first.id })
        assertFalse(projected.any { it.id == t2.first.id })
    }

    @Test
    fun toolLoopStaysOneDialogueTurn() {
        val user = ChatMessage(
            id = "u1",
            text = "search",
            participant = Participant.USER,
            status = MessageStatus.SUCCESS,
        )
        val tool = ChatMessage(
            id = "${Constants.TOOL_MSG_PREFIX}t1",
            text = "call",
            participant = Participant.MODEL,
            parentId = user.id,
            status = MessageStatus.SUCCESS,
            runId = "run-a",
        )
        val result = ChatMessage(
            id = "${Constants.RESULT_MSG_PREFIX}r1",
            text = "hits",
            participant = Participant.USER,
            parentId = tool.id,
            status = MessageStatus.SUCCESS,
            runId = "run-a",
        )
        val mid = ChatMessage(
            id = "a_mid",
            text = "calling",
            participant = Participant.MODEL,
            parentId = result.id,
            status = MessageStatus.SUCCESS,
            runId = "run-a",
        )
        val assistant = ChatMessage(
            id = "a_final",
            text = "done",
            participant = Participant.MODEL,
            parentId = mid.id,
            status = MessageStatus.SUCCESS,
            runId = "run-b",
            turnId = "turn_1",
            integrateText = "merged whole turn",
        )
        val live = ChatMessage(
            id = "u_live",
            text = "next",
            participant = Participant.USER,
            status = MessageStatus.SUCCESS,
            parentId = assistant.id,
        )
        val projected = applyIntegrationProjection(
            messages = listOf(user, tool, result, mid, assistant, live),
            retainTurns = 0,
        )
        assertEquals(1, projected.count { it.id.startsWith(TimeBandPolicy.TURN_INTEGRATION_ID_PREFIX) })
        assertTrue(projected.none { it.id.startsWith(Constants.TOOL_MSG_PREFIX) })
        assertTrue(projected.none { it.id.startsWith(Constants.RESULT_MSG_PREFIX) })
        assertTrue(projected.none { it.id == user.id })
        assertTrue(projected.any { it.id == live.id })
        assertTrue(projected.single { it.id.startsWith(TimeBandPolicy.TURN_INTEGRATION_ID_PREFIX) }.text.contains("merged whole turn"))
    }

    @Test
    fun cacheMissKeepsOriginalsOutsideRetain() {
        val t1 = turn("a1", "turn_1", integrate = null)
        val t2 = turn("a2", "turn_2", integrate = "ready")
        val projected = applyIntegrationProjection(
            messages = listOf(t1.first, t1.second, t2.first, t2.second),
            retainTurns = 1,
        )
        assertTrue(projected.any { it.id == t1.first.id })
        assertTrue(projected.any { it.id == "${TimeBandPolicy.TURN_INTEGRATION_ID_PREFIX}turn_2" }.not())
        assertEquals(t2.first.id, projected.first { it.id == t2.first.id }.id)
    }

    @Test
    fun keepsSuccessfulCompactCapsuleOnPath() {
        val t1 = turn("a1", "turn_1", integrate = "merged")
        val compact = ChatMessage(
            id = "${Constants.COMPACT_MSG_PREFIX}boundary",
            text = "capsule",
            participant = Participant.MODEL,
            status = MessageStatus.SUCCESS,
            parentId = t1.second.id,
        )
        val t2 = turn("a2", "turn_2", integrate = "later")
        val projected = applyIntegrationProjection(
            messages = listOf(t1.first, t1.second, compact, t2.first, t2.second),
            retainTurns = 1,
        )
        assertTrue(projected.any { it.id == compact.id })
        assertTrue(projected.any { it.id == "${TimeBandPolicy.TURN_INTEGRATION_ID_PREFIX}turn_1" })
        assertTrue(projected.any { it.id == t2.first.id })
        assertFalse(projected.any { it.id == t1.first.id })
    }

    @Test
    fun compressionRatio_isIntegratedOverOriginal() {
        data class Case(val name: String, val original: Int, val integrated: Int, val ratio: Double?)
        val cases = listOf(
            Case("one third remains", 300, 100, 0.33),
            Case("rounds to two decimals", 3, 10, 3.33),
            Case("zero original", 0, 10, null),
            Case("zero integrated", 10, 0, null),
        )
        cases.forEach { case ->
            assertEquals(case.name, case.ratio, compressionRatio(case.original, case.integrated))
        }
    }

    @Test
    fun readingFormatReturnsIntegrationBody() {
        val json = encodeTurnIntegrationsJson(
            listOf(TurnIntegrationItem(turn_id = "turn_x", integration = "Merged facts.")),
        )
        val reading = formatTurnIntegrationForReading(json)
        assertTrue(reading!!.contains("turn_x"))
        assertTrue(reading.contains("Merged facts."))
        assertEquals(null, formatTurnIntegrationForReading("not json"))
        assertEquals(
            "Old body",
            formatTurnIntegrationForReading(
                """{"type":"turn_integration","turn_id":"t","integration":"Old body"}""",
            ),
        )
    }

    @Test
    fun dialogueTurnIndexing_countsOpeningsNotAssistantRunsOrSyntheticUsers() {
        data class Case(val name: String, val messages: List<ChatMessage>, val turns: Int)
        val user = { id: String, turnId: String? ->
            ChatMessage(
                id = id,
                text = id,
                participant = Participant.USER,
                status = MessageStatus.SUCCESS,
                turnId = turnId,
                integrateText = turnId?.let { "i_$it" },
            )
        }
        val assistant = { id: String, runId: String, turnId: String? ->
            ChatMessage(
                id = id,
                text = id,
                participant = Participant.MODEL,
                status = MessageStatus.SUCCESS,
                runId = runId,
                turnId = turnId,
                integrateText = turnId?.let { "i_$it" },
            )
        }
        val tool = ChatMessage(
            id = "${Constants.TOOL_MSG_PREFIX}t",
            text = "call",
            participant = Participant.MODEL,
            status = MessageStatus.SUCCESS,
        )
        val result = ChatMessage(
            id = "${Constants.RESULT_MSG_PREFIX}r",
            text = "out",
            participant = Participant.USER,
            status = MessageStatus.SUCCESS,
        )
        val imageContext = ChatMessage(
            id = "image_context_abc",
            text = "inspect image",
            participant = Participant.USER,
            status = MessageStatus.SUCCESS,
        )
        val cases = listOf(
            Case(
                name = "interrupted user plus tools is one turn",
                messages = listOf(user("u1", "turn_1"), tool, result),
                turns = 1,
            ),
            Case(
                name = "tool loop stays one turn",
                messages = listOf(
                    user("u1", null),
                    assistant("a1", "run-a", null),
                    tool,
                    result,
                    assistant("a2", "run-b", "turn_1"),
                ),
                turns = 1,
            ),
            Case(
                name = "image_context does not start a turn",
                messages = listOf(
                    user("u1", null),
                    assistant("a1", "r1", null),
                    tool,
                    result,
                    imageContext,
                    assistant("a2", "r2", "turn_1"),
                ),
                turns = 1,
            ),
            Case(
                name = "two user openings are two turns",
                messages = listOf(
                    user("u1", "turn_1"),
                    assistant("a1", "r1", "turn_1"),
                    user("u2", "turn_2"),
                ),
                turns = 2,
            ),
        )
        cases.forEach { case ->
            assertEquals(
                case.name,
                case.turns,
                CompactTurnIndexer.indexDialogueTurns(case.messages).size,
            )
        }
    }

    @Test
    fun overflowProjectionHidesInterruptedAndLeadingProtocolWhenCached() {
        val leadingTool = ChatMessage(
            id = "${Constants.TOOL_MSG_PREFIX}lead",
            text = "lead",
            participant = Participant.MODEL,
            status = MessageStatus.SUCCESS,
        )
        val leadingResult = ChatMessage(
            id = "${Constants.RESULT_MSG_PREFIX}lead",
            text = "lead-out",
            participant = Participant.USER,
            status = MessageStatus.SUCCESS,
        )
        val interrupted = ChatMessage(
            id = "u_interrupt",
            text = "stop",
            participant = Participant.USER,
            status = MessageStatus.SUCCESS,
            turnId = "turn_1",
            integrateText = "interrupted turn",
        )
        val tool = ChatMessage(
            id = "${Constants.TOOL_MSG_PREFIX}t1",
            text = "call",
            participant = Participant.MODEL,
            status = MessageStatus.SUCCESS,
        )
        val result = ChatMessage(
            id = "${Constants.RESULT_MSG_PREFIX}r1",
            text = "hits",
            participant = Participant.USER,
            status = MessageStatus.SUCCESS,
        )
        val imageContext = ChatMessage(
            id = "image_context_abc",
            text = "inspect image",
            participant = Participant.USER,
            status = MessageStatus.SUCCESS,
        )
        val laterUser = ChatMessage(
            id = "u2",
            text = "next",
            participant = Participant.USER,
            status = MessageStatus.SUCCESS,
        )
        val laterAssistant = ChatMessage(
            id = "a2",
            text = "ok",
            participant = Participant.MODEL,
            status = MessageStatus.SUCCESS,
            turnId = "turn_2",
            integrateText = "second turn",
        )
        val projected = applyIntegrationProjection(
            messages = listOf(
                leadingTool,
                leadingResult,
                interrupted,
                tool,
                result,
                imageContext,
                laterUser,
                laterAssistant,
            ),
            retainTurns = 0,
        )
        assertEquals(1, projected.count { it.id.startsWith(TimeBandPolicy.TURN_INTEGRATION_ID_PREFIX) })
        assertTrue(projected.none { it.id.startsWith(Constants.TOOL_MSG_PREFIX) })
        assertTrue(projected.none { it.id.startsWith(Constants.RESULT_MSG_PREFIX) })
        assertTrue(projected.none { it.id == interrupted.id })
        assertTrue(projected.none { it.id == imageContext.id })
        assertTrue(projected.none { it.id == laterUser.id })
        val bundle = projected.single { it.id.startsWith(TimeBandPolicy.TURN_INTEGRATION_ID_PREFIX) }
        assertTrue(bundle.text.contains("turn_1"))
        assertTrue(bundle.text.contains("turn_2"))
        assertEquals(0, projected.indexOf(bundle))
    }

    @Test
    fun applyContextCompressionSkipsArchiveWhenDisabled() {
        val user = ChatMessage(
            id = "u_a1",
            text = "user",
            participant = Participant.USER,
            status = MessageStatus.SUCCESS,
        )
        val assistant = ChatMessage(
            id = "a1",
            text = "assistant",
            participant = Participant.MODEL,
            status = MessageStatus.SUCCESS,
            parentId = user.id,
            turnId = "turn_1",
            compactBatchId = "batch_x",
            compactSummary = "Sum",
            compactDigest = "World",
            compactDigestRev = 1,
        )
        val projected = applyContextCompression(
            messages = listOf(user, assistant),
            projectSummaryBatches = true,
            retainTurns = 0,
            archiveBandEnabled = false,
        )
        assertFalse(projected.any { it.id.startsWith("context_digest_") })
        assertFalse(projected.any { it.id.startsWith("context_batch_") })
    }

    private fun turn(
        assistantId: String,
        turnId: String,
        locked: Boolean = false,
        integrate: String? = null,
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
            integrateText = integrate,
        )
        return user to assistant
    }
}
