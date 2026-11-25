package com.newoether.agora.viewmodel

import android.content.Context
import com.newoether.agora.automation.ConversationExecutionCoordinator
import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.local.UiMessageGraphRow
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.util.DebugLog
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationUiStateAssemblerTest {
    @Test
    fun `room graph becomes ready atomically before runtime overlay is published`() = runTest {
        val conversations = mockk<ConversationRepository>()
        val registry = mockk<ConversationStateRegistry>()
        val executionCoordinator = mockk<ConversationExecutionCoordinator>()
        val state = generationState(isLoading = true, generating = false)
        val roomMessages = MutableSharedFlow<List<UiMessageGraphRow>>(replay = 1)
        coEvery { conversations.ensureRunRecovery() } returns Unit
        coEvery { conversations.fixStuckMessages(CONVERSATION_ID) } returns Unit
        coEvery { conversations.getConversation(CONVERSATION_ID) } returns ChatEntity(
            id = CONVERSATION_ID,
            title = "Conversation",
            selectedBranchesJson = "{\"null\":\"root\"}",
        )
        every { conversations.observeUiMessageGraph(CONVERSATION_ID) } returns
            roomMessages
        every { conversations.observeSendTokenCache(CONVERSATION_ID) } returns
            flowOf(SendTokenCache())
        every { registry.getOrCreate(CONVERSATION_ID) } returns state
        every { executionCoordinator.activeAutomationConversationIds } returns
            MutableStateFlow(emptySet())
        val currentConversationId = MutableStateFlow<String?>(null)
        val assembler = ConversationUiStateAssembler(
            conversations = conversations,
            registry = registry,
            executionCoordinator = executionCoordinator,
            currentConversationId = currentConversationId,
            appContext = mockk<Context>(relaxed = true),
            scope = backgroundScope,
            projectionDispatcher = StandardTestDispatcher(testScheduler),
        )
        assembler.start()

        currentConversationId.value = CONVERSATION_ID
        runCurrent()
        assertNull(assembler.loadedMessagesConversationId.value)
        assertFalse(assembler.isLoading.value)

        roomMessages.emit(emptyList())
        runCurrent()

        assertEquals(CONVERSATION_ID, assembler.loadedMessagesConversationId.value)
        assertEquals(mapOf<String?, String>(null to "root"), assembler.renderStore.selectedChildren)
        assertTrue(assembler.isLoading.value)
        assertNull(assembler.generatingInConversationId.value)
        coVerify(exactly = 1) { conversations.ensureRunRecovery() }
        coVerify(exactly = 1) { conversations.fixStuckMessages(CONVERSATION_ID) }

        currentConversationId.value = null
        runCurrent()
        assertNull(assembler.loadedMessagesConversationId.value)
        assertFalse(assembler.isLoading.value)
        assertNull(assembler.generatingInConversationId.value)
        assertEquals(emptyList<ChatMessage>(), assembler.renderStore.allMessages)
    }

    @Test
    fun `active automation prevents foreground recovery from stopping its live row`() = runTest {
        val conversations = mockk<ConversationRepository>()
        val registry = mockk<ConversationStateRegistry>()
        val executionCoordinator = mockk<ConversationExecutionCoordinator>()
        val state = generationState(isLoading = false, generating = false)
        coEvery { conversations.ensureRunRecovery() } returns Unit
        coEvery { conversations.getConversation(CONVERSATION_ID) } returns ChatEntity(
            id = CONVERSATION_ID,
            title = "Conversation",
        )
        every { conversations.observeUiMessageGraph(CONVERSATION_ID) } returns
            MutableStateFlow(emptyList())
        every { conversations.observeSendTokenCache(CONVERSATION_ID) } returns
            flowOf(SendTokenCache())
        every { registry.getOrCreate(CONVERSATION_ID) } returns state
        every { executionCoordinator.activeAutomationConversationIds } returns
            MutableStateFlow(setOf(CONVERSATION_ID))
        val currentConversationId = MutableStateFlow<String?>(CONVERSATION_ID)
        val assembler = ConversationUiStateAssembler(
            conversations = conversations,
            registry = registry,
            executionCoordinator = executionCoordinator,
            currentConversationId = currentConversationId,
            appContext = mockk<Context>(relaxed = true),
            scope = backgroundScope,
            projectionDispatcher = StandardTestDispatcher(testScheduler),
        )

        assembler.start()
        runCurrent()

        assertEquals(CONVERSATION_ID, assembler.loadedMessagesConversationId.value)
        coVerify(exactly = 0) { conversations.fixStuckMessages(any()) }
    }

    @Test
    fun `late callbacks for another conversation cannot change open projection state`() = runTest {
        val currentConversationId = MutableStateFlow<String?>(CONVERSATION_ID)
        val conversations = mockk<ConversationRepository>()
        every { conversations.observeSendTokenCache(CONVERSATION_ID) } returns
            flowOf(SendTokenCache())
        val assembler = ConversationUiStateAssembler(
            conversations = conversations,
            registry = mockk(),
            executionCoordinator = mockk(),
            currentConversationId = currentConversationId,
            appContext = mockk(relaxed = true),
            scope = backgroundScope,
            projectionDispatcher = StandardTestDispatcher(testScheduler),
        )

        assembler.markActive("other")
        assertFalse(assembler.isLoading.value)
        assertNull(assembler.generatingInConversationId.value)

        assembler.markActive(CONVERSATION_ID)
        assembler.markIdle("other")
        assertTrue(assembler.isLoading.value)
        assertEquals(CONVERSATION_ID, assembler.generatingInConversationId.value)
    }

    @Test
    fun `current projection failure is reported without replacing the selection`() = runTest {
        mockkObject(DebugLog)
        every { DebugLog.e(any(), any(), any()) } returns Unit
        try {
            val conversations = mockk<ConversationRepository>()
            every { conversations.observeSendTokenCache(CONVERSATION_ID) } returns
                flowOf(SendTokenCache())
            coEvery { conversations.ensureRunRecovery() } throws IllegalStateException("failed")
            val failedIds = mutableListOf<String>()
            val assembler = ConversationUiStateAssembler(
                conversations = conversations,
                registry = mockk(),
                executionCoordinator = mockk(),
                currentConversationId = MutableStateFlow(CONVERSATION_ID),
                appContext = mockk(relaxed = true),
                scope = backgroundScope,
                projectionDispatcher = StandardTestDispatcher(testScheduler),
                onConversationLoadFailed = failedIds::add,
            )
            assembler.renderStore.replaceConversation(
                allMessages = listOf(
                    ChatMessage(
                        id = "old-message",
                        text = "old projection",
                        participant = Participant.USER,
                        status = MessageStatus.SUCCESS,
                        timestamp = 1L,
                    )
                ),
                selectedChildren = mapOf(null to "old-message"),
            )
            assembler.markActive(CONVERSATION_ID)

            assembler.start()
            runCurrent()

            assertEquals(listOf(CONVERSATION_ID), failedIds)
            assertNull(assembler.loadedMessagesConversationId.value)
            assertFalse(assembler.isLoading.value)
            assertNull(assembler.generatingInConversationId.value)
            assertEquals(emptyList<ChatMessage>(), assembler.renderStore.allMessages)
            assertEquals(emptyMap<String?, String>(), assembler.renderStore.selectedChildren)
        } finally {
            unmockkObject(DebugLog)
        }
    }

    @Test
    fun contextGraphRevision_ignoresBodyText() {
        val first = ChatMessage(
            id = "a",
            text = "short",
            participant = Participant.USER,
            status = MessageStatus.SUCCESS,
        )
        val sameIdentity = first.copy(text = "a much longer body that must not change the revision")
        assertEquals(contextGraphRevision(listOf(first)), contextGraphRevision(listOf(sameIdentity)))
        val digested = first.copy(compactDigestRev = 2)
        assertTrue(contextGraphRevision(listOf(first)) != contextGraphRevision(listOf(digested)))
    }

    @Test
    fun incrementalGraph_doesNotRefetchUnchangedOrStreamingRows() {
        val stored = ChatMessage(
            id = "a1",
            text = "answer",
            participant = Participant.MODEL,
            status = MessageStatus.SUCCESS,
            turnId = "t1",
        )
        val rows = listOf(
            UiMessageGraphRow("a1", null, MessageStatus.SUCCESS, 0, null, "t1"),
            UiMessageGraphRow("live", "a1", MessageStatus.SENDING, 0, null, null),
        )
        val previous = mapOf(
            "a1" to ProjectedUiMessage(rows[0].fingerprint(), stored),
        )
        val ids = incrementalUiMessageIdsToFetch(
            rows = rows,
            previous = previous,
            existingById = mapOf("a1" to stored),
            streamingMessageId = "live",
        )
        assertEquals(emptyList<String>(), ids)
    }

    private fun generationState(
        isLoading: Boolean,
        generating: Boolean,
    ): ConversationGenerationState = mockk<ConversationGenerationState>().also { state ->
        every { state.streamingMessage } returns MutableStateFlow(null)
        every { state.isLoading } returns MutableStateFlow(isLoading)
        every { state.generating } returns MutableStateFlow(generating)
        every { state.generationSnapshot } returns MutableStateFlow(
            ConversationGenerationSnapshot(
                conversationId = CONVERSATION_ID,
                isLoading = isLoading,
                isGenerating = generating,
            ),
        )
    }

    private companion object {
        const val CONVERSATION_ID = "conversation"
    }
}
