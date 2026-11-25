package com.newoether.agora.tool

import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.local.CompactMessageGraphRow
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.viewmodel.GenerationContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RagToolProviderIsolationTest {
    private val conversations = mockk<ConversationRepository>()
    private val provider = RagToolProvider(conversations)
    private val context = GenerationContext(accessPastConversations = true)

    @Test
    fun listConversations_readsOnlySearchableConversationSource() = runTest {
        coEvery { conversations.getSearchableConversationsList() } returns listOf(
            ChatEntity(id = "visible", title = "Visible", lastUpdated = 123L)
        )

        val result = Json.parseToJsonElement(
            provider.execute("list_conversations", "{}", context)
        ).jsonObject

        assertEquals(1, result.getValue("total").jsonPrimitive.content.toInt())
        assertEquals(
            "visible",
            result.getValue("conversations").jsonArray.single().jsonObject
                .getValue("id").jsonPrimitive.content,
        )
        coVerify(exactly = 1) { conversations.getSearchableConversationsList() }
        coVerify(exactly = 0) { conversations.getAllConversationsList() }
    }

    @Test
    fun readConversation_rejectsHiddenConversationBeforeReadingMessages() = runTest {
        coEvery { conversations.getSearchableConversation("hidden") } returns null

        val result = Json.parseToJsonElement(
            provider.execute(
                "read_conversation",
                "{\"conversation_id\":\"hidden\"}",
                context,
            )
        ).jsonObject

        assertEquals("not_found", result.getValue("error").jsonPrimitive.content)
    }

    @Test
    fun keywordSearch_dropsStaleHiddenMatchBeforeWindowExpansion() = runTest {
        val hiddenMatch = MessageEntity(
            id = "message-hidden",
            conversationId = "hidden",
            text = "private task result",
            status = MessageStatus.SUCCESS,
            participant = Participant.MODEL,
            timestamp = 123L,
            runId = "run-hidden",
            runSequence = 0,
        )
        coEvery { conversations.searchMessages("private", any()) } returns listOf(hiddenMatch)
        coEvery { conversations.getSearchableConversation("hidden") } returns null

        val result = Json.parseToJsonElement(
            provider.execute(
                "search_conversations",
                "{\"query\":\"private\"}",
                context,
            )
        ).jsonObject

        assertTrue(result.getValue("results").jsonArray.isEmpty())
        coVerify(exactly = 0) { conversations.getCompactMessageGraph(any()) }
        coVerify(exactly = 0) { conversations.getMessagesByIds(any()) }
    }

    @Test
    fun searchConversations_emitsCountField() = runTest {
        val match = MessageEntity(
            id = "message-1",
            conversationId = "conv",
            text = "hello target",
            status = MessageStatus.SUCCESS,
            participant = Participant.MODEL,
            timestamp = 123L,
            runId = "run-1",
            runSequence = 0,
        )
        coEvery { conversations.searchMessages("target", any()) } returns listOf(match)
        coEvery { conversations.getSearchableConversation("conv") } returns ChatEntity(id = "conv", title = "Conv", lastUpdated = 123L)
        coEvery { conversations.getCompactMessageGraph("conv") } returns listOf(match.toGraphRow())
        coEvery { conversations.getMessagesByIds(listOf("message-1")) } returns listOf(match)

        val result = Json.parseToJsonElement(
            provider.execute("search_conversations", """{"query":"target"}""", context)
        ).jsonObject

        assertEquals(1, result.getValue("count").jsonPrimitive.content.toInt())
        assertEquals(1, result.getValue("results").jsonArray.size)
        coVerify(exactly = 1) { conversations.getCompactMessageGraph("conv") }
        coVerify(exactly = 1) { conversations.getMessagesByIds(listOf("message-1")) }
    }

    @Test
    fun readConversation_hydratesTheSelectedPageWithoutAWholeTableSnapshot() = runTest {
        val user = MessageEntity(
            id = "user",
            conversationId = "conv",
            text = "hello",
            status = MessageStatus.SUCCESS,
            participant = Participant.USER,
            timestamp = 1L,
            runId = "run-1",
            runSequence = 0,
        )
        val model = MessageEntity(
            id = "model",
            conversationId = "conv",
            parentId = "user",
            text = "world",
            status = MessageStatus.SUCCESS,
            participant = Participant.MODEL,
            timestamp = 2L,
            runId = "run-1",
            runSequence = 1,
        )
        coEvery { conversations.getSearchableConversation("conv") } returns
            ChatEntity(id = "conv", title = "Conv", lastUpdated = 2L)
        coEvery { conversations.getCompactMessageGraph("conv") } returns listOf(
            user.toGraphRow(),
            model.toGraphRow(),
        )
        coEvery { conversations.getMessagesByIds(any()) } returns listOf(user, model)

        val result = Json.parseToJsonElement(
            provider.execute(
                "read_conversation",
                """{"conversation_id":"conv","offset":0,"limit":50}""",
                context,
            )
        ).jsonObject

        assertEquals(2, result.getValue("total_messages").jsonPrimitive.content.toInt())
        assertEquals(2, result.getValue("messages").jsonArray.size)
        coVerify(exactly = 1) { conversations.getCompactMessageGraph("conv") }
        coVerify(exactly = 1) { conversations.getMessagesByIds(any()) }
    }

    private fun MessageEntity.toGraphRow() = CompactMessageGraphRow(
        id = id,
        conversationId = conversationId,
        parentId = parentId,
        participant = participant,
        status = status,
        timestamp = timestamp,
        runId = runId,
        runSequence = runSequence,
        modelName = modelName,
        consumedAtPass = consumedAtPass,
        turnId = turnId,
        compactLocked = compactLocked,
        compactBatchId = compactBatchId,
        compactDigestRev = compactDigestRev,
        hasCompactDigest = !compactDigest.isNullOrBlank(),
    )
}
