package com.newoether.agora.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsContractsTest {
    @Test
    fun automaticContextCompactIsEnabledByDefault() {
        assertTrue(DEFAULT_CONTEXT_COMPACT_ENABLED)
    }

    @Test
    fun contextCompactBatchSizeDefaultsToEight() {
        assertEquals(8, DEFAULT_CONTEXT_COMPACT_BATCH_SIZE)
        assertEquals(DEFAULT_CONTEXT_COMPACT_BATCH_SIZE, DEFAULT_CONTEXT_COMPACT_RETAIN_COUNT)
    }

    @Test
    fun legacyPromptContentResolvesToOneCustomSystemItem() {
        val prompt = SystemPromptEntry(
            title = "Legacy",
            content = "Preserve this prompt",
        )

        val resolved = prompt.resolvedSystemItems.single()
        assertEquals(PromptItemType.CUSTOM, resolved.type)
        assertEquals("Preserve this prompt", resolved.value)
    }

    @Test
    fun explicitSystemItemsTakePrecedenceOverLegacyContent() {
        val explicit = listOf(
            PromptTemplateItem(type = PromptItemType.CUSTOM, value = "Explicit"),
        )

        assertEquals(
            explicit,
            SystemPromptEntry(
                title = "Current",
                content = "Legacy",
                systemItems = explicit,
            ).resolvedSystemItems,
        )
    }

    @Test
    fun conversationSettingsReportsWhetherAnyOverrideExists() {
        assertTrue(ConversationToolState().isAllNull())
        assertFalse(ConversationToolState(openAiWebSearchEnabled = false).isAllNull())
        assertFalse(ConversationToolState(shellEnabled = false).isAllNull())
        assertFalse(ConversationToolState(askModeEnabled = false).isAllNull())
    }
    @Test
    fun unknownOrRemovedSearchProviderFallsBackToZhipu() {
        assertEquals("zhipu", normalizeWebSearchProvider(" OpenAI "))
        assertEquals("zhipu", normalizeWebSearchProvider(" unknown "))
        assertEquals("zhipu", normalizeWebSearchProvider(null))
        assertEquals("kagi", normalizeWebSearchProvider(" KAGI "))
    }

    @Test
    fun retiredAppLanguagesCollapseToSupportedCodes() {
        assertEquals("en", normalizeAppLanguage("en"))
        assertEquals("zh", normalizeAppLanguage("zh"))
        assertEquals("zh", normalizeAppLanguage("zh-Hant"))
        assertEquals("system", normalizeAppLanguage("ja"))
        assertEquals("system", normalizeAppLanguage("pt-BR"))
        assertEquals("system", normalizeAppLanguage(null))
        assertEquals("system", normalizeAppLanguage(" system "))
    }
}
