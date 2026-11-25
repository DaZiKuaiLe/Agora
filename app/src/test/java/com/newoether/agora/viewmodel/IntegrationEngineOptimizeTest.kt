package com.newoether.agora.viewmodel

import android.content.Context
import com.newoether.agora.data.BuiltInPrompts
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.tool.DetailStore
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.io.path.createTempDirectory

class IntegrationEngineOptimizeTest {
    @Test
    fun optimizeAll_doesNotReadBodiesWhenIntegrationBandIsOff() {
        val conversations = mockk<ConversationRepository>(relaxed = true)
        val result = runBlocking {
            IntegrationEngine(
                conversations = conversations,
                detailStore = DetailStore(createTempDirectory("integrate-opt").toFile()),
                providers = mockk(relaxed = true),
            ).optimizeAll(
                conversationId = "conversation",
                config = AutomaticCompactConfig(
                    enabled = true,
                    compactThreshold = 1_000,
                    request = CompactRequest(
                        model = "openai:gpt",
                        prompt = BuiltInPrompts.CONTEXT_INTEGRATE_SYSTEM,
                        retainLogicalMessages = 2,
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
                        effectiveSystemPrompt = BuiltInPrompts.CONTEXT_INTEGRATE_SYSTEM,
                        codeExecutionEnabled = false,
                        googleSearchEnabled = false,
                        thinkingEnabled = false,
                        baseUrl = null,
                    ),
                    providerInstances = emptyMap(),
                    generationContext = GenerationContext(),
                ),
                appContext = mockk<Context>(relaxed = true),
                onProgress = {},
            )
        }
        assertEquals(CompactResult.NotNeeded, result)
        coVerify(exactly = 0) { conversations.getCompactMessageGraph(any()) }
        coVerify(exactly = 0) { conversations.getMessagesByIds(any()) }
    }
}
