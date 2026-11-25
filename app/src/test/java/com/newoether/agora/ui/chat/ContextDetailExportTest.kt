package com.newoether.agora.ui.chat

import com.newoether.agora.api.util.ContextTokenEstimator
import com.newoether.agora.api.util.ContextWindowUsage
import com.newoether.agora.api.util.SummaryBatchItem
import com.newoether.agora.api.util.TurnIntegrationItem
import com.newoether.agora.api.util.compressionRatio
import com.newoether.agora.api.util.encodeContextDigestJson
import com.newoether.agora.api.util.encodeSummaryBatchesJson
import com.newoether.agora.api.util.encodeTurnIntegrationsJson
import com.newoether.agora.api.util.estimateIntegratedTurnTokens
import com.newoether.agora.data.TimeBandPolicy
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.ToolCallData
import com.newoether.agora.util.Constants
import com.newoether.agora.viewmodel.ConversationContextDetail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextDetailExportTest {
    @Test
    fun `export json is role content array with system first`() {
        val detail = ConversationContextDetail(
            systemPrompt = "You are helpful.",
            messages = listOf(
                ChatMessage(
                    id = "u1",
                    text = "Hello **world**",
                    participant = Participant.USER,
                ),
                ChatMessage(
                    id = "a1",
                    text = "Hi",
                    participant = Participant.MODEL,
                ),
                ChatMessage(
                    id = "${Constants.TOOL_MSG_PREFIX}1",
                    text = "",
                    participant = Participant.MODEL,
                    toolCall = ToolCallData(
                        toolName = "shell",
                        arguments = """{"cmd":"ls"}""",
                        result = "",
                        toolCallId = "c1",
                    ),
                ),
                ChatMessage(
                    id = "${Constants.RESULT_MSG_PREFIX}1",
                    text = "ok",
                    participant = Participant.MODEL,
                    toolCall = ToolCallData(
                        toolName = "shell",
                        arguments = """{"cmd":"ls"}""",
                        result = "file.txt",
                        toolCallId = "c1",
                    ),
                ),
            ),
            usage = ContextWindowUsage(
                estimatedTokenCount = 10,
                tokenBudget = 100,
                logicalMessageCount = 2,
                hasCompactBoundary = false,
            ),
            fixedTokenCost = 1,
            messageTokenBudget = 99,
        )

        val arr = Json.parseToJsonElement(buildContextDetailExportJson(detail)).jsonArray
        assertEquals(5, arr.size)
        assertEquals("system", arr[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("You are helpful.", arr[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("user", arr[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("Hello **world**", arr[1].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("u1", arr[1].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("assistant", arr[2].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals(null, arr[2].jsonObject["id"])
        assertEquals("tool", arr[3].jsonObject["role"]!!.jsonPrimitive.content)
        assertTrue(arr[3].jsonObject["content"]!!.jsonPrimitive.content.contains("tool: shell"))
        assertEquals("tool_result", arr[4].jsonObject["role"]!!.jsonPrimitive.content)
        assertTrue(arr[4].jsonObject["content"]!!.jsonPrimitive.content.contains("result: file.txt"))
    }

    @Test
    fun `export file name includes conversation id`() {
        val named = contextDetailExportFileName("conv-abc/def:1")
        assertTrue(named.startsWith("agora_context_conv-abc_def_1_"))
        assertTrue(named.endsWith(".json"))
        assertTrue(contextDetailExportFileName(null).startsWith("agora_context_new_"))
        assertTrue(contextDetailExportFileName("  ").startsWith("agora_context_new_"))
    }

    @Test
    fun `export compact batches include origin messages`() {
        val user = ChatMessage(
            id = "u1",
            text = "raw user",
            participant = Participant.USER,
            status = MessageStatus.SUCCESS,
        )
        val assistant = ChatMessage(
            id = "a1",
            text = "raw assistant",
            participant = Participant.MODEL,
            status = MessageStatus.SUCCESS,
            parentId = user.id,
            turnId = "turn_1",
            compactBatchId = "batch_x",
            compactSummary = "Sum X",
        )
        val wire = encodeSummaryBatchesJson(
            listOf(SummaryBatchItem(batchId = "batch_x", summary = "Sum X")),
        )
        val detail = ConversationContextDetail(
            systemPrompt = "sys",
            messages = listOf(
                ChatMessage(
                    id = "context_batch_batch_x",
                    text = wire,
                    participant = Participant.USER,
                    status = MessageStatus.SUCCESS,
                ),
            ),
            usage = ContextWindowUsage(1, 100, 1, false),
            fixedTokenCost = 0,
            messageTokenBudget = 100,
            exportSourceMessages = listOf(user, assistant),
        )

        val arr = Json.parseToJsonElement(buildContextDetailExportJson(detail)).jsonArray
        assertEquals("compact", arr[1].jsonObject["role"]!!.jsonPrimitive.content)
        val compactContent = Json.parseToJsonElement(
            arr[1].jsonObject["content"]!!.jsonPrimitive.content,
        ).jsonObject
        assertEquals("summary_batches", compactContent["type"]!!.jsonPrimitive.content)
        val batch = compactContent["batches"]!!.jsonArray[0].jsonObject
        assertEquals("batch_x", batch["batchId"]!!.jsonPrimitive.content)
        val origin = batch["origin"]!!.jsonArray
        assertEquals(2, origin.size)
        assertEquals("user", origin[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("raw user", origin[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("u1", origin[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("assistant", origin[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("raw assistant", origin[1].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `export digest includes batch summary and origin on notes`() {
        val user = ChatMessage(
            id = "u1",
            text = "raw user",
            participant = Participant.USER,
            status = MessageStatus.SUCCESS,
        )
        val assistant = ChatMessage(
            id = "a1",
            text = "raw assistant",
            participant = Participant.MODEL,
            status = MessageStatus.SUCCESS,
            parentId = user.id,
            turnId = "turn_1",
            compactBatchId = "batch_x",
            compactSummary = "Sum X",
            compactDigest = "Live: done.",
            compactDigestRev = 2,
        )
        val wire = encodeContextDigestJson(
            rev = 2,
            digest = "Live: done.",
        )
        val detail = ConversationContextDetail(
            systemPrompt = "sys",
            messages = listOf(
                ChatMessage(
                    id = "context_digest_2",
                    text = wire,
                    participant = Participant.USER,
                    status = MessageStatus.SUCCESS,
                ),
            ),
            usage = ContextWindowUsage(1, 100, 1, false),
            fixedTokenCost = 0,
            messageTokenBudget = 100,
            exportSourceMessages = listOf(user, assistant),
        )

        val arr = Json.parseToJsonElement(buildContextDetailExportJson(detail)).jsonArray
        assertEquals("compact", arr[1].jsonObject["role"]!!.jsonPrimitive.content)
        val compactContent = Json.parseToJsonElement(
            arr[1].jsonObject["content"]!!.jsonPrimitive.content,
        ).jsonObject
        assertEquals("context_digest", compactContent["type"]!!.jsonPrimitive.content)
        val note = compactContent["notes"]!!.jsonArray[0].jsonObject
        assertEquals("batch_x", note["id"]!!.jsonPrimitive.content)
        assertEquals("Sum X", note["summary"]!!.jsonPrimitive.content)
        val origin = note["origin"]!!.jsonArray
        assertEquals(2, origin.size)
        assertEquals("user", origin[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("raw user", origin[0].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `export plain digest still attaches origin`() {
        val user = ChatMessage(
            id = "u1",
            text = "raw user",
            participant = Participant.USER,
            status = MessageStatus.SUCCESS,
        )
        val assistant = ChatMessage(
            id = "a1",
            text = "raw assistant",
            participant = Participant.MODEL,
            status = MessageStatus.SUCCESS,
            parentId = user.id,
            turnId = "turn_1",
            compactBatchId = "summary",
            compactDigest = "Live: done.",
            compactDigestRev = 2,
        )
        val detail = ConversationContextDetail(
            systemPrompt = "sys",
            messages = listOf(
                ChatMessage(
                    id = "context_digest_2",
                    text = "Live: done.",
                    participant = Participant.USER,
                    status = MessageStatus.SUCCESS,
                ),
            ),
            usage = ContextWindowUsage(1, 100, 1, false),
            fixedTokenCost = 0,
            messageTokenBudget = 100,
            exportSourceMessages = listOf(user, assistant),
        )

        val arr = Json.parseToJsonElement(buildContextDetailExportJson(detail)).jsonArray
        assertEquals("compact", arr[1].jsonObject["role"]!!.jsonPrimitive.content)
        val compactContent = Json.parseToJsonElement(
            arr[1].jsonObject["content"]!!.jsonPrimitive.content,
        ).jsonObject
        assertEquals("context_digest", compactContent["type"]!!.jsonPrimitive.content)
        assertEquals("Live: done.", compactContent["digest"]!!.jsonPrimitive.content)
        val origin = compactContent["notes"]!!.jsonArray[0].jsonObject["origin"]!!.jsonArray
        assertEquals(2, origin.size)
        assertEquals("raw user", origin[0].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `export json attaches origin to turn_integrations`() {
        val user = ChatMessage(
            id = "u1",
            text = "raw user",
            participant = Participant.USER,
            status = MessageStatus.SUCCESS,
        )
        val assistant = ChatMessage(
            id = "a1",
            text = "raw assistant",
            participant = Participant.MODEL,
            status = MessageStatus.SUCCESS,
            parentId = user.id,
            turnId = "turn_1",
            integrateText = "Merged",
        )
        val wire = encodeTurnIntegrationsJson(
            listOf(TurnIntegrationItem(turn_id = "turn_1", integration = "Merged")),
        )
        val detail = ConversationContextDetail(
            systemPrompt = "sys",
            messages = listOf(
                ChatMessage(
                    id = "${TimeBandPolicy.TURN_INTEGRATION_ID_PREFIX}turn_1",
                    text = wire,
                    participant = Participant.USER,
                    status = MessageStatus.SUCCESS,
                ),
            ),
            usage = ContextWindowUsage(1, 100, 1, false),
            fixedTokenCost = 0,
            messageTokenBudget = 100,
            exportSourceMessages = listOf(user, assistant),
        )

        val arr = Json.parseToJsonElement(buildContextDetailExportJson(detail)).jsonArray
        assertEquals("compact", arr[1].jsonObject["role"]!!.jsonPrimitive.content)
        val compactContent = Json.parseToJsonElement(
            arr[1].jsonObject["content"]!!.jsonPrimitive.content,
        ).jsonObject
        assertEquals("turn_integrations", compactContent["type"]!!.jsonPrimitive.content)
        val turn = compactContent["turns"]!!.jsonArray[0].jsonObject
        assertEquals("turn_1", turn["turn_id"]!!.jsonPrimitive.content)
        val origin = turn["origin"]!!.jsonArray
        assertEquals(2, origin.size)
        assertEquals("user", origin[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("raw user", origin[0].jsonObject["content"]!!.jsonPrimitive.content)
        val originalTokens = ContextTokenEstimator.estimate(listOf(user, assistant))
        val integratedTokens = estimateIntegratedTurnTokens("Merged")
        assertEquals(originalTokens, turn["original_tokens"]!!.jsonPrimitive.int)
        assertEquals(integratedTokens, turn["integrated_tokens"]!!.jsonPrimitive.int)
        val expectedRatio = compressionRatio(originalTokens, integratedTokens)
        assertTrue(expectedRatio != null)
        assertEquals(expectedRatio!!, turn["compression_ratio"]!!.jsonPrimitive.double, 0.0)
    }

    @Test
    fun freezeChapterCountReadsWireProjection() {
        data class Case(
            val name: String,
            val id: String,
            val text: String,
            val expected: Int,
        )
        val cases = listOf(
            Case("plain user", "u1", "hello", 0),
            Case("single digest", "context_digest_1", "Prior facts", 0),
            Case(
                "two frozen chapters",
                "context_digest_2",
                "[整合段 1/2]\nOld${com.newoether.agora.data.RESIDENCY_CHAPTER_WIRE_BREAK}[整合段 2/2]\nNew",
                2,
            ),
        )
        for (case in cases) {
            val count = contextDetailFreezeChapterCount(
                listOf(
                    ChatMessage(
                        id = case.id,
                        text = case.text,
                        participant = Participant.USER,
                        status = MessageStatus.SUCCESS,
                    ),
                ),
            )
            assertEquals(case.name, case.expected, count)
        }
    }

    @Test
    fun processCompletionNoticeExportOmitsStdoutDump() {
        val dump = "【系统提示】\nprocess: 后台进程已结束\n\noutput:\nsecret-dump"
        val message = ChatMessage(
            id = "${Constants.PROCESS_NOTICE_MSG_PREFIX}1",
            text = dump,
            participant = Participant.USER,
        )
        assertEquals("—", contextDetailExportBody(message))
        val detail = ConversationContextDetail(
            systemPrompt = "sys",
            messages = listOf(message),
            usage = ContextWindowUsage(1, 100, 1, false),
            fixedTokenCost = 0,
            messageTokenBudget = 100,
        )
        val arr = Json.parseToJsonElement(buildContextDetailExportJson(detail)).jsonArray
        assertEquals("user", arr[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("—", arr[1].jsonObject["content"]!!.jsonPrimitive.content)
        assertTrue(arr[1].jsonObject["content"]!!.jsonPrimitive.content.contains("secret-dump").not())
    }
}
