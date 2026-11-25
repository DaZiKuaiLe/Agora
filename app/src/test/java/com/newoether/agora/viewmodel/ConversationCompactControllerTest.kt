package com.newoether.agora.viewmodel

import com.newoether.agora.data.BuiltInPrompts
import com.newoether.agora.data.repository.ConversationRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationCompactControllerTest {
    @Test
    fun disabledSettingShortCircuitsBeforeReadingDurableGraph() {
        runBlocking {
            val conversations = mockk<ConversationRepository>()
            val batchEngine = BatchCompactEngine(
                conversations = conversations,
                detailStore = mockk(relaxed = true),
                providers = mockk(relaxed = true),
            )
            val compactor = ContextCompactor(batchEngine = batchEngine)

            assertFalse(
                compactor.automaticNeeded(
                    "conversation",
                    automaticConfig().copy(enabled = false),
                ),
            )
            coVerify(exactly = 0) {
                conversations.getCompactMessageGraph(any())
                conversations.restoreBranchSelections(any())
                conversations.getSendTokenCache(any())
            }
        }
    }

    @Test
    fun automaticStandardUsesContinuationParentWithoutCapsuleLaunch() {
        runBlocking {
            val batchEngine = mockk<BatchCompactEngine>()
            val state = ConversationGenerationState("conversation")
            coEvery { batchEngine.automaticNeeded("conversation", any()) } returns true
            coEvery {
                batchEngine.ensureFormalBatches(
                    conversationId = "conversation",
                    config = any(),
                    fullRecompact = any(),
                    onProgress = any(),
                )
            } returns CompactResult.Created("batch_formal_1")

            val started = ConversationCompactController(
                batchEngine = batchEngine,
                requestBuilder = null,
            ).startAutomaticStandard(
                conversationId = "conversation",
                config = automaticConfig(),
                state = state,
                continuationParentId = "parent_msg",
            )

            assertNotNull(started)
            assertEquals("parent_msg", started!!.messageId)
            started.job.join()
            coVerify(exactly = 1) {
                batchEngine.ensureFormalBatches(
                    conversationId = "conversation",
                    config = any(),
                    fullRecompact = any(),
                    onProgress = any(),
                )
            }
            state.dispose()
        }
    }

    @Test
    fun automaticNotNeededSkipsLaunch() {
        runBlocking {
            val batchEngine = mockk<BatchCompactEngine>()
            val state = ConversationGenerationState("conversation")
            coEvery { batchEngine.automaticNeeded("conversation", any()) } returns false

            val started = ConversationCompactController(
                batchEngine = batchEngine,
                requestBuilder = null,
            ).startAutomaticStandard(
                conversationId = "conversation",
                config = automaticConfig(),
                state = state,
                continuationParentId = "parent_msg",
            )

            assertEquals(null, started)
            coVerify(exactly = 0) { batchEngine.ensureFormalBatches(any(), any()) }
            state.dispose()
        }
    }

    @Test
    fun compactFormatStale_readsConversationRowNotMessageBodies() {
        runBlocking {
            data class Case(
                val name: String,
                val storedRev: Int,
                val hasArtifacts: Boolean,
                val stale: Boolean,
            )
            val cases = listOf(
                Case("behind with artifacts", storedRev = 0, hasArtifacts = true, stale = true),
                Case("current with artifacts", storedRev = com.newoether.agora.data.CompactFormat.CURRENT_REV, hasArtifacts = true, stale = false),
                Case("no artifacts", storedRev = 0, hasArtifacts = false, stale = false),
            )
            for (case in cases) {
                val conversations = mockk<ConversationRepository>()
                coEvery { conversations.getConversation("conversation") } returns
                    com.newoether.agora.data.local.ChatEntity(
                        id = "conversation",
                        title = "t",
                        compactFormatRev = case.storedRev,
                    )
                coEvery { conversations.conversationHasCompactArtifacts("conversation") } returns
                    case.hasArtifacts
                val stale = BatchCompactEngine(
                    conversations = conversations,
                    detailStore = mockk(relaxed = true),
                    providers = mockk(relaxed = true),
                ).compactFormatStale("conversation")
                assertEquals(case.name, case.stale, stale)
                coVerify(exactly = 0) {
                    conversations.getCompactMessageGraph(any())
                    conversations.restoreBranchSelections(any())
                }
            }
        }
    }

    @Test
    fun automaticNeeded_usesSeatedLedgerWithoutReadingGraph() {
        runBlocking {
            val conversations = mockk<ConversationRepository>()
            coEvery { conversations.getSendTokenCache("conversation") } returns SendTokenCache(
                tokens = 400_000,
                countedTurnId = "t8|0",
                budget = 524_288,
                retainTurns = 8,
                fixedCost = 40,
                configKey = "k",
                prefixTokens = 2_000,
            )
            val needed = BatchCompactEngine(
                conversations = conversations,
                detailStore = mockk(relaxed = true),
                providers = mockk(relaxed = true),
            ).automaticNeeded("conversation", automaticConfig().copy(compactThreshold = 1_000))
            assertTrue(needed)
            coVerify(exactly = 0) {
                conversations.getCompactMessageGraph(any())
                conversations.restoreBranchSelections(any())
            }
        }
    }

    @Test
    fun manualCompactIsIncrementalNotFullRecompact() {
        runBlocking {
            val batchEngine = mockk<BatchCompactEngine>()
            val requestBuilder = mockk<GenerationRequestBuilder>()
            val state = ConversationGenerationState("conversation")
            val snapshot = testGenerationAdmissionSnapshot(
                conversationId = "conversation",
                selectedModelId = "openai:gpt",
            )
            coEvery {
                requestBuilder.captureAdmissionSnapshot("conversation", any(), "openai:gpt")
            } returns snapshot
            coEvery {
                batchEngine.ensureFormalBatches(
                    conversationId = "conversation",
                    config = any(),
                    fullRecompact = false,
                    onProgress = any(),
                )
            } returns CompactResult.Created("batch_formal_2")

            val result = ConversationCompactController(
                batchEngine = batchEngine,
                requestBuilder = requestBuilder,
            ).manual(
                conversationId = "conversation",
                request = CompactRequest(
                    model = "openai:gpt",
                    prompt = "ignored",
                    retainLogicalMessages = 8,
                    fullRecompact = false,
                ),
                state = state,
            )

            assertTrue(result is CompactResult.Created)
            coVerify {
                batchEngine.ensureFormalBatches(
                    conversationId = "conversation",
                    config = any(),
                    fullRecompact = false,
                    onProgress = any(),
                )
            }
            state.dispose()
        }
    }

    @Test
    fun legacyFullRecompactPassesFlag() {
        runBlocking {
            val batchEngine = mockk<BatchCompactEngine>()
            val requestBuilder = mockk<GenerationRequestBuilder>()
            val state = ConversationGenerationState("conversation")
            val snapshot = testGenerationAdmissionSnapshot(
                conversationId = "conversation",
                selectedModelId = "openai:gpt",
            )
            coEvery {
                requestBuilder.captureAdmissionSnapshot("conversation", any(), "openai:gpt")
            } returns snapshot
            coEvery {
                batchEngine.ensureFormalBatches(
                    conversationId = "conversation",
                    config = any(),
                    fullRecompact = true,
                    onProgress = any(),
                )
            } returns CompactResult.Created("batch_formal_3")

            val result = ConversationCompactController(
                batchEngine = batchEngine,
                requestBuilder = requestBuilder,
            ).manual(
                conversationId = "conversation",
                request = CompactRequest(
                    model = "openai:gpt",
                    prompt = "ignored",
                    retainLogicalMessages = 8,
                    fullRecompact = true,
                ),
                state = state,
            )

            assertTrue(result is CompactResult.Created)
            coVerify {
                batchEngine.ensureFormalBatches(
                    conversationId = "conversation",
                    config = any(),
                    fullRecompact = true,
                    onProgress = any(),
                )
            }
            state.dispose()
        }
    }

    private fun automaticConfig() = AutomaticCompactConfig(
        enabled = true,
        compactThreshold = 1_000,
        request = CompactRequest(
            model = "openai:gpt",
            prompt = BuiltInPrompts.CONTEXT_COMPACT_SYSTEM,
            retainLogicalMessages = 8,
        ),
        providerName = "openai",
        apiKey = "key",
        baseUrl = null,
        provider = mockk(relaxed = true),
        configured = true,
        generationConfig = GenerationConfig(
            providerName = "openai",
            modelId = "gpt",
            apiKey = "key",
            effectiveSystemPrompt = BuiltInPrompts.CONTEXT_COMPACT_SYSTEM,
            codeExecutionEnabled = false,
            googleSearchEnabled = false,
            thinkingEnabled = false,
            baseUrl = null,
        ),
        providerInstances = emptyMap(),
        generationContext = GenerationContext(),
    )
}
