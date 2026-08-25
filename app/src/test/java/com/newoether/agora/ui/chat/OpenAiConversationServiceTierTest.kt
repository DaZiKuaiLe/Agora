package com.newoether.agora.ui.chat

import com.newoether.agora.data.CustomEndpointProtocol
import com.newoether.agora.data.CustomProviderConfig
import com.newoether.agora.model.OpenAiServiceTiers
import com.newoether.agora.util.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiConversationServiceTierTest {
    @Test
    fun globalTierIsNormalizedAndEnabledTracksGlobalFlag() {
        val inherited = resolveOpenAiConversationServiceTier(
            globalEnabled = true,
            globalTier = OpenAiServiceTiers.FLEX,
            providerName = Constants.PROVIDER_OPENAI,
            builtInOpenAiResponsesEnabled = true,
            customProviders = emptyList(),
        )
        assertTrue(inherited.available)
        assertTrue(inherited.enabled)
        assertEquals(OpenAiServiceTiers.FLEX, inherited.tier)

        val disabled = resolveOpenAiConversationServiceTier(
            globalEnabled = false,
            globalTier = OpenAiServiceTiers.FAST,
            providerName = Constants.PROVIDER_OPENAI,
            builtInOpenAiResponsesEnabled = true,
            customProviders = emptyList(),
        )
        assertFalse(disabled.enabled)
        assertEquals(OpenAiServiceTiers.FAST, disabled.tier)
    }

    @Test
    fun availabilityFollowsSelectedProviderProtocol() {
        val customProviders = listOf(
            CustomProviderConfig(
                name = "OpenAI relay",
                protocol = CustomEndpointProtocol.OPENAI,
                responsesApiEnabled = true,
            ),
            CustomProviderConfig(
                name = "Anthropic relay",
                protocol = CustomEndpointProtocol.ANTHROPIC,
            ),
        )

        assertTrue(
            resolveOpenAiConversationServiceTier(
                globalEnabled = false,
                globalTier = OpenAiServiceTiers.AUTO,
                providerName = "OpenAI relay",
                builtInOpenAiResponsesEnabled = false,
                customProviders = customProviders,
            ).available,
        )
        assertFalse(
            resolveOpenAiConversationServiceTier(
                globalEnabled = false,
                globalTier = OpenAiServiceTiers.AUTO,
                providerName = "Anthropic relay",
                builtInOpenAiResponsesEnabled = false,
                customProviders = customProviders,
            ).available,
        )
    }

    @Test
    fun availabilityRequiresResponsesForBuiltInAndCustomProviders() {
        assertFalse(
            resolveOpenAiConversationServiceTier(
                globalEnabled = true,
                globalTier = OpenAiServiceTiers.FAST,
                providerName = Constants.PROVIDER_OPENAI,
                builtInOpenAiResponsesEnabled = false,
                customProviders = emptyList(),
            ).available,
        )
        assertFalse(
            resolveOpenAiConversationServiceTier(
                globalEnabled = true,
                globalTier = OpenAiServiceTiers.FAST,
                providerName = "Relay without Responses",
                builtInOpenAiResponsesEnabled = true,
                customProviders = listOf(
                    CustomProviderConfig(
                        name = "Relay without Responses",
                        protocol = CustomEndpointProtocol.OPENAI,
                        responsesApiEnabled = false,
                    ),
                ),
            ).available,
        )
    }
}
