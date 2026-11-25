package com.newoether.agora.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import com.newoether.agora.api.util.ContextTokenEstimator
import com.newoether.agora.api.util.ContextWindowUsage
import com.newoether.agora.tool.CompactTurnIndexer
import com.newoether.agora.viewmodel.ChatViewModel
import com.newoether.agora.viewmodel.ConversationContextProjection
import com.newoether.agora.viewmodel.OccupancyDisplay
import com.newoether.agora.viewmodel.isLastTranscriptPage
import com.newoether.agora.viewmodel.transcriptLedgerCursor
import com.newoether.agora.viewmodel.SendTokenCache
import com.newoether.agora.viewmodel.persistSendTokenCache
import com.newoether.agora.viewmodel.refreshDisplayedOccupancy
import com.newoether.agora.viewmodel.sendTokenConfigKey

/** Every value capable of changing fixed system-prompt or tool-definition token cost. */
@Composable
internal fun rememberContextProjectionInvalidationKey(
    viewModel: ChatViewModel,
    requestConfiguration: List<Any?>,
): List<Any?> {
    val settings = viewModel.settings
    val activePromptId by settings.activeSystemPromptId.collectAsState()
    val pendingPromptId by viewModel.pendingSystemPromptId.collectAsState()
    val systemPrompts by settings.systemPrompts.collectAsState()
    val accessSavedMemories by settings.accessSavedMemories.collectAsState()
    val accessSkills by settings.accessSkills.collectAsState()
    val accessSkillsModify by settings.accessSkillsModify.collectAsState()
    val accessPastConversations by settings.accessPastConversations.collectAsState()
    val imageGenEnabled by settings.imageGenEnabled.collectAsState()
    val imageGenModel by settings.imageGenModel.collectAsState()
    val automationToolsEnabled by settings.automationToolsEnabled.collectAsState()
    val sandboxEnabled by settings.sandboxEnabled.collectAsState()
    val sandboxSharedStorageEnabled by settings.sandboxSharedStorageEnabled.collectAsState()
    val mcpServers by viewModel.mcpServerSnapshots.collectAsState()
    val activeMemoryRevision by viewModel.memoryManager.activeMemoryRevision.collectAsState()
    val skillCatalogRevision by viewModel.skillManager.catalogRevision.collectAsState()
    return requestConfiguration + listOf(
        activePromptId,
        pendingPromptId,
        systemPrompts,
        accessSavedMemories,
        accessSkills,
        accessSkillsModify,
        accessPastConversations,
        imageGenEnabled,
        imageGenModel,
        automationToolsEnabled,
        sandboxEnabled,
        sandboxSharedStorageEnabled,
        mcpServers,
        activeMemoryRevision,
        skillCatalogRevision,
    )
}

/** Shows the saved occupancy. A mismatch does not reread the conversation. */
@Composable
internal fun rememberConversationContextProjection(
    viewModel: ChatViewModel,
    conversationId: String?,
    selectedModelId: String,
    tokenBudget: Int,
    visualizeContextRollout: Boolean,
    toolConfigurationKey: Any,
): ConversationContextProjection {
    val messages by viewModel.messages.collectAsState()
    val cache by viewModel.sendTokenCache.collectAsState()
    val retainTurns by viewModel.settings.contextCompactRetainCount.collectAsState()
    val isLastPage by viewModel.isLastTranscriptPage.collectAsState()
    val ledgerCursor by viewModel.transcriptLedgerCursor.collectAsState()
    val configKey = remember(selectedModelId, tokenBudget, retainTurns, toolConfigurationKey) {
        sendTokenConfigKey(selectedModelId, tokenBudget, retainTurns, toolConfigurationKey)
    }
    return produceState(
        initialValue = savedOccupancy(cache, tokenBudget),
        conversationId,
        selectedModelId,
        tokenBudget,
        ledgerCursor,
        visualizeContextRollout,
        isLastPage,
        configKey,
        cache,
        retainTurns,
    ) {
        val display = refreshDisplayedOccupancy(
            cache = cache,
            configKey = configKey,
            tokenBudget = tokenBudget,
            retainTurns = retainTurns,
            ledgerCursor = ledgerCursor,
            onLastPage = isLastPage,
            turns = CompactTurnIndexer.finishedDialogueTurns(messages),
            estimateTurn = { turn -> ContextTokenEstimator.estimate(turn.messages) },
        )
        val persisted = display.persist
        if (persisted != null && conversationId != null) {
            runCatching { viewModel.persistSendTokenCache(conversationId, persisted) }
        }
        value = display.toProjection(tokenBudget)
    }.value
}

private fun savedOccupancy(cache: SendTokenCache, tokenBudget: Int) =
    OccupancyDisplay(cache.tokens, cache.fixedCost, cache.countedTurnId).toProjection(tokenBudget)

private fun OccupancyDisplay.toProjection(tokenBudget: Int) = ConversationContextProjection(
    usage = ContextWindowUsage(
        estimatedTokenCount = tokens,
        tokenBudget = tokenBudget,
        logicalMessageCount = 0,
        hasCompactBoundary = false,
    ),
    retainedMessageIds = emptySet(),
    fixedTokenCost = fixedCost,
    lastTurnId = countedTurnId,
)
