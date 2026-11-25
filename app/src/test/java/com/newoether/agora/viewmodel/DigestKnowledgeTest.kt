package com.newoether.agora.viewmodel

import com.newoether.agora.data.DefaultSystemPrompt
import com.newoether.agora.data.PromptItemType
import com.newoether.agora.data.PromptTemplateItem
import com.newoether.agora.data.SystemPromptEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DigestKnowledgeTest {
    @Test
    fun digestBackgroundStale_matchesRevAndPromptIdentityRules() {
        data class Case(
            val name: String,
            val stored: DigestBackgroundSnapshot,
            val current: DigestBackgroundSnapshot,
            val stale: Boolean,
        )
        val cases = listOf(
            Case(
                "am rev advanced",
                DigestBackgroundSnapshot(activeMemoryRev = 1, promptId = "p", promptRev = 0),
                DigestBackgroundSnapshot(activeMemoryRev = 2, promptId = "p", promptRev = 0),
                true,
            ),
            Case(
                "prompt rev advanced",
                DigestBackgroundSnapshot(activeMemoryRev = 1, promptId = "p", promptRev = 3),
                DigestBackgroundSnapshot(activeMemoryRev = 1, promptId = "p", promptRev = 4),
                true,
            ),
            Case(
                "prompt id changed",
                DigestBackgroundSnapshot(activeMemoryRev = 0, promptId = "old", promptRev = 0),
                DigestBackgroundSnapshot(activeMemoryRev = 0, promptId = "new", promptRev = 0),
                true,
            ),
            Case(
                "pre-feature null prompt id is not stale",
                DigestBackgroundSnapshot(activeMemoryRev = 0, promptId = null, promptRev = 0),
                DigestBackgroundSnapshot(activeMemoryRev = 0, promptId = "p", promptRev = 0),
                false,
            ),
            Case(
                "revs match",
                DigestBackgroundSnapshot(activeMemoryRev = 4, promptId = "p", promptRev = 2),
                DigestBackgroundSnapshot(activeMemoryRev = 4, promptId = "p", promptRev = 2),
                false,
            ),
        )
        for (c in cases) {
            assertEquals(c.name, c.stale, digestBackgroundStale(c.stored, c.current))
        }
    }

    @Test
    fun digestPromptPersona_skipsPolicyAndFactoryDefault_keepsCustom() {
        fun entry(text: String) = SystemPromptEntry(
            title = "Custom",
            systemItems = listOf(PromptTemplateItem(type = PromptItemType.CUSTOM, value = text)),
        )
        assertNull(digestPromptPersona(entry("You are helpful.\nContext compression (Agora):\npolicy")))
        assertNull(digestPromptPersona(entry("人设\n上下文压缩（Agora）：\n政策")))
        assertNull(digestPromptPersona(DefaultSystemPrompt.create()))
        assertEquals("Always answer in French.", digestPromptPersona(entry("Always answer in French.")))
    }
}
