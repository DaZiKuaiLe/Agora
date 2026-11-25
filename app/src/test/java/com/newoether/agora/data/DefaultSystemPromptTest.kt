package com.newoether.agora.data

import com.newoether.agora.data.DefaultSystemPrompt
import com.newoether.agora.tool.ProcessTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class DefaultSystemPromptTest {
    @Test
    fun titleForLocale_usesChineseDefaultForChineseLocale() {
        assertEquals("Default", DefaultSystemPrompt.titleForLocale(Locale.ENGLISH))
        assertEquals("\u9ed8\u8ba4", DefaultSystemPrompt.titleForLocale(Locale.SIMPLIFIED_CHINESE))
        assertEquals("\u9ed8\u8ba4", DefaultSystemPrompt.titleForLocale(Locale.forLanguageTag("zh-Hant")))
        assertEquals("Default", DefaultSystemPrompt.titleForLocale(Locale.forLanguageTag("es")))
        assertEquals("Default", DefaultSystemPrompt.titleForLocale(Locale.FRENCH))
    }

    @Test
    fun create_keepsPersonaToolsAndOmitsBakedCompressionPolicy() {
        val entry = DefaultSystemPrompt.create(Locale.ENGLISH)
        val systemPrompt = PredefinedVariables.compile(
            entry.systemItems,
            mapOf(
                PredefinedVariables.ACTIVE_MEMORY to "User prefers concise answers."
            )
        )

        assertFalse(systemPrompt.contains("<agora_runtime_context>"))
        assertTrue(systemPrompt.contains("<active_memory_context>\nUser prefers concise answers.\n</active_memory_context>"))
        assertFalse(AGORA_POLICY_SEED_MARKER in systemPrompt)
        assertFalse(AGORA_POLICY_PREFIX_HEADING in systemPrompt)
        assertFalse(systemPrompt.contains("context_digest"))
        assertTrue(ProcessTools.START_LOCAL in systemPrompt)
        assertTrue(ProcessTools.LIST in systemPrompt)
        assertTrue(ProcessTools.BACKGROUND_EXIT_NOTIFY in systemPrompt)
    }

    @Test
    fun hasOldRuntimeContext_detectsOldEntries() {
        val entry = DefaultSystemPrompt.create(Locale.ENGLISH)
        assertFalse(DefaultSystemPrompt.hasOldRuntimeContext(entry))

        // Simulate an old entry by injecting a custom item with the legacy tag
        val oldItems = entry.systemItems.toMutableList()
        oldItems.add(0, PromptTemplateItem(type = PromptItemType.CUSTOM, value = "prefix <agora_runtime_context> old content"))
        val oldEntry = entry.copy(systemItems = oldItems)
        assertTrue(DefaultSystemPrompt.hasOldRuntimeContext(oldEntry))
    }

    @Test
    fun create_leavesUserAffixesEmpty() {
        val entry = DefaultSystemPrompt.create(Locale.ENGLISH)
        assertTrue(entry.userPrependItems.isEmpty())
        assertTrue(entry.userPostpendItems.isEmpty())
    }

    @Test
    fun migrateAgoraUserMessageWrap_replacesPairedXmlWrap() {
        val old = SystemPromptEntry(
            title = "Default",
            systemItems = listOf(PromptTemplateItem(type = PromptItemType.CUSTOM, value = "Keep me")),
            userPrependItems = listOf(
                PromptTemplateItem(type = PromptItemType.CUSTOM, value = "<agora_user_message sent_date=\""),
                PromptTemplateItem(type = PromptItemType.PREDEFINED, value = PredefinedVariables.SENT_DATE),
                PromptTemplateItem(type = PromptItemType.CUSTOM, value = "\" sent_time=\""),
                PromptTemplateItem(type = PromptItemType.PREDEFINED, value = PredefinedVariables.SENT_TIME),
                PromptTemplateItem(type = PromptItemType.CUSTOM, value = "\">\n"),
            ),
            userPostpendItems = listOf(
                PromptTemplateItem(type = PromptItemType.CUSTOM, value = "\n</agora_user_message>"),
            ),
        )
        val migrated = migrateAgoraUserMessageWrap(old)
        assertTrue(migrated.userPrependItems.isEmpty())
        assertTrue(migrated.userPostpendItems.isEmpty())
        assertEquals("Keep me", migrated.resolvedSystemItems.single().value)
    }

    @Test
    fun migrateAgoraUserMessageWrap_stripsOrphanCloseTag() {
        val old = SystemPromptEntry(
            title = "Custom",
            systemItems = listOf(PromptTemplateItem(type = PromptItemType.CUSTOM, value = "思考过程尽量使用中文返回")),
            userPostpendItems = listOf(
                PromptTemplateItem(type = PromptItemType.CUSTOM, value = "\n</agora_user_message>"),
            ),
        )
        val migrated = migrateAgoraUserMessageWrap(old)
        assertTrue(migrated.userPrependItems.isEmpty())
        assertTrue(migrated.userPostpendItems.isEmpty())
        assertEquals("思考过程尽量使用中文返回", migrated.resolvedSystemItems.single().value)
    }

    @Test
    fun migrateAgoraUserMessageWrap_replacesOpenTagWithoutClose() {
        val old = SystemPromptEntry(
            title = "Default",
            userPrependItems = listOf(
                PromptTemplateItem(type = PromptItemType.CUSTOM, value = "<agora_user_message sent_date=\""),
                PromptTemplateItem(type = PromptItemType.PREDEFINED, value = PredefinedVariables.SENT_DATE),
                PromptTemplateItem(type = PromptItemType.CUSTOM, value = "\" sent_time=\""),
                PromptTemplateItem(type = PromptItemType.PREDEFINED, value = PredefinedVariables.SENT_TIME),
                PromptTemplateItem(type = PromptItemType.CUSTOM, value = "\">\n"),
            ),
        )
        val migrated = migrateAgoraUserMessageWrap(old)
        assertTrue(migrated.userPrependItems.isEmpty())
        assertTrue(migrated.userPostpendItems.isEmpty())
    }

    @Test
    fun migrateFactoryUserTimePrefix_clearsShippedPrefixOnly() {
        val shipped = SystemPromptEntry(
            title = "Default",
            userPrependItems = listOf(
                PromptTemplateItem(type = PromptItemType.CUSTOM, value = "sent_date="),
                PromptTemplateItem(type = PromptItemType.PREDEFINED, value = PredefinedVariables.SENT_DATE),
                PromptTemplateItem(type = PromptItemType.CUSTOM, value = " sent_time="),
                PromptTemplateItem(type = PromptItemType.PREDEFINED, value = PredefinedVariables.SENT_TIME),
                PromptTemplateItem(type = PromptItemType.CUSTOM, value = "\n"),
            ),
        )
        assertTrue(migrateAgoraUserMessageWrap(shipped).userPrependItems.isEmpty())

        val custom = SystemPromptEntry(
            title = "Custom",
            userPrependItems = listOf(
                PromptTemplateItem(type = PromptItemType.CUSTOM, value = "note "),
                PromptTemplateItem(type = PromptItemType.PREDEFINED, value = PredefinedVariables.SENT_TIME),
            ),
        )
        val kept = migrateAgoraUserMessageWrap(custom)
        assertEquals(
            custom.userPrependItems.map { it.type to it.value },
            kept.userPrependItems.map { it.type to it.value },
        )
    }

    @Test
    fun migrateAgoraUserMessageWrap_leavesUserAuthoredAffixes() {
        val custom = SystemPromptEntry(
            title = "Custom",
            userPrependItems = listOf(
                PromptTemplateItem(type = PromptItemType.CUSTOM, value = "[user]\n"),
            ),
            userPostpendItems = listOf(
                PromptTemplateItem(type = PromptItemType.CUSTOM, value = "\n</agora_user_message>"),
            ),
        )
        assertEquals(custom, migrateAgoraUserMessageWrap(custom))
    }
}
