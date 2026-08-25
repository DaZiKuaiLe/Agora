package com.newoether.agora.api.openai

import com.newoether.agora.api.OpenAiChatRequest
import com.newoether.agora.api.OpenAiMessage
import com.newoether.agora.api.ProviderConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeepSeekProviderTest {
    /** Exposes the protected customizeRequest so the test can assert on the wire mapping. */
    private class TestableDeepSeekProvider : DeepSeekProvider() {
        fun customize(request: OpenAiChatRequest, config: ProviderConfig): OpenAiChatRequest =
            customizeRequest(request, config)
    }

    private val provider = TestableDeepSeekProvider()

    private fun request() = OpenAiChatRequest(
        model = "deepseek-v4-flash",
        messages = listOf(OpenAiMessage(role = "user", content = null)),
    )

    private fun config(
        thinkingEnabled: Boolean = true,
        thinkingLevel: String = "medium",
        modelId: String = "deepseek-v4-flash",
    ) = ProviderConfig(
        apiKey = "sk-test",
        modelId = modelId,
        thinkingEnabled = thinkingEnabled,
        thinkingLevel = thinkingLevel,
    )

    @Test
    fun v4ThinkingEnabled_setsEffortAndEnablesThinking() {
        val customized = provider.customize(request(), config())
        assertEquals("high", customized.reasoningEffort)
        assertEquals("enabled", customized.thinking?.type)
    }

    @Test
    fun v4MaxEffort_mapsToMax() {
        val customized = provider.customize(request(), config(thinkingLevel = "max"))
        assertEquals("max", customized.reasoningEffort)
        assertEquals("enabled", customized.thinking?.type)
    }

    @Test
    fun v4XhighEffort_collapsesToHigh() {
        val customized = provider.customize(request(), config(thinkingLevel = "xhigh"))
        assertEquals("high", customized.reasoningEffort)
        assertEquals("enabled", customized.thinking?.type)
    }

    @Test
    fun v4ThinkingDisabled_turnsThinkingOff() {
        val customized = provider.customize(request(), config(thinkingEnabled = false))
        assertNull(customized.reasoningEffort)
        assertEquals("disabled", customized.thinking?.type)
    }

    @Test
    fun legacyModel_isUnchanged() {
        val legacy = request().copy(model = "deepseek-reasoner")
        val customized = provider.customize(legacy, config(modelId = "deepseek-reasoner"))
        assertNull(customized.reasoningEffort)
        assertNull(customized.thinking)
    }

    @Test
    fun v4Enabled_serializesToOfficialDeepSeekShape() {
        val customized = provider.customize(request(), config(thinkingLevel = "max"))
        // Match BaseOpenAiProvider's default encodeDefaults=true so type defaults are serialized.
        val json = Json { encodeDefaults = true; explicitNulls = false }
        val encoded = json.encodeToString(OpenAiChatRequest.serializer(), customized)
        val obj = Json.parseToJsonElement(encoded).jsonObject
        assertEquals("max", obj["reasoning_effort"]?.jsonPrimitive?.content)
        assertEquals("enabled", obj["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
    }
}
