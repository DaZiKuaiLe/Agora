package com.newoether.agora.viewmodel

import com.newoether.agora.data.AGORA_POLICY_PREFIX_HEADING
import com.newoether.agora.data.AGORA_POLICY_SEED_MARKER
import com.newoether.agora.data.DefaultSystemPrompt
import com.newoether.agora.data.MemoryManager
import com.newoether.agora.data.PredefinedVariables
import com.newoether.agora.data.SystemPromptEntry
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.SettingsRepository

internal data class DigestKnowledgeSnapshot(
    val activeMemory: String = "",
    val activeMemoryRev: Long = 0L,
    val promptId: String? = null,
    val promptRev: Long = 0L,
    val promptPersona: String? = null,
)

internal data class DigestBackgroundSnapshot(
    val activeMemoryRev: Long = 0L,
    val promptId: String? = null,
    val promptRev: Long = 0L,
)

internal fun interface DigestKnowledgeSource {
    suspend fun snapshot(conversationId: String): DigestKnowledgeSnapshot
}

internal class SettingsDigestKnowledgeSource(
    private val conversations: ConversationRepository,
    private val settings: SettingsRepository,
    private val memory: MemoryManager,
) : DigestKnowledgeSource {
    override suspend fun snapshot(conversationId: String): DigestKnowledgeSnapshot {
        val conversation = conversations.getConversation(conversationId)
        val promptId = conversation?.systemPromptId ?: settings.activeSystemPromptId.value
        val entry = settings.systemPrompts.value.find { it.id == promptId }
        memory.ensureRevisionForExistingContent()
        val activeMemory = memory.getActiveMemory()
        return DigestKnowledgeSnapshot(
            activeMemory = activeMemory,
            activeMemoryRev = memory.getActiveMemoryRev(),
            promptId = promptId,
            promptRev = entry?.rev ?: 0L,
            promptPersona = digestPromptPersona(entry),
        )
    }
}

/** Custom persona for digest authors; skip Agora runtime policy and the factory Default. */
internal fun digestPromptPersona(entry: SystemPromptEntry?): String? {
    if (entry == null) return null
    val compiled = PredefinedVariables.compile(entry.resolvedSystemItems, emptyMap())
    if (AGORA_POLICY_SEED_MARKER in compiled) return null
    if (AGORA_POLICY_PREFIX_HEADING in compiled) return null
    if (DefaultSystemPrompt.FACTORY_SEED_MARKER in compiled) return null
    return compiled.trim().takeIf { it.isNotBlank() }
}

internal fun digestBackgroundStale(
    stored: DigestBackgroundSnapshot,
    current: DigestBackgroundSnapshot,
): Boolean {
    if (stored.promptId != null && stored.promptId != current.promptId) return true
    if (stored.activeMemoryRev < current.activeMemoryRev) return true
    if (stored.promptRev < current.promptRev) return true
    return false
}
