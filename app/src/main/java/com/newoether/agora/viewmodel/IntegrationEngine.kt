package com.newoether.agora.viewmodel

import android.content.Context
import com.newoether.agora.api.LocalModelSerializer
import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.StreamEvent
import com.newoether.agora.api.util.formatTurnOriginals
import com.newoether.agora.data.BuiltInPrompts
import com.newoether.agora.data.TimeBandPolicy
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.ModelId
import com.newoether.agora.model.Participant
import com.newoether.agora.tool.CompactTurn
import com.newoether.agora.tool.CompactTurnIndexer
import com.newoether.agora.tool.DetailStore
import com.newoether.agora.tool.isReadyToIntegrate
import com.newoether.agora.util.Constants
import com.newoether.agora.util.DebugLog
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Best-effort per-turn integration cache. Does not take the summary-compact conversation lock
 * and must not block send. Full optimize is the explicit synchronous path.
 */
internal class IntegrationEngine(
    private val conversations: ConversationRepository,
    private val detailStore: DetailStore,
    private val providers: ProviderRegistry,
) {
    private val turnLocks = ConcurrentHashMap<String, Mutex>()

    suspend fun prefetchAndSweep(
        conversationId: String,
        config: AutomaticCompactConfig,
    ) {
        if (!TimeBandPolicy.INTEGRATION_BAND_ENABLED) return
        if (!config.configured || config.provider == null || config.request.model.isBlank()) return
        val path = loadStubPath(conversationId)
        val indexed = CompactTurnIndexer.indexDialogueTurns(path)
        if (indexed.isEmpty()) return
        ensureDialogueTurnIds(conversationId, indexed)
        val turns = CompactTurnIndexer.indexDialogueTurns(loadStubPath(conversationId))
        val missing = turns.filter { turn ->
            turn.isReadyToIntegrate() &&
                !turn.locked &&
                !turn.turnId.startsWith("pending_") &&
                turn.integrateText.isNullOrBlank()
        }
        for (turn in missing) {
            integrateTurn(conversationId, turn.turnId, config, force = false)
        }
    }

    suspend fun optimizeAll(
        conversationId: String,
        config: AutomaticCompactConfig,
        @Suppress("UNUSED_PARAMETER") appContext: Context,
        onProgress: (CompactBatchProgress) -> Unit,
    ): CompactResult {
        if (!config.configured || config.provider == null || config.request.model.isBlank()) {
            return CompactResult.Failed(CompactFailureReason.SETUP_UNAVAILABLE)
        }
        if (!TimeBandPolicy.INTEGRATION_BAND_ENABLED) {
            return CompactResult.NotNeeded
        }
        val path = loadStubPath(conversationId)
        ensureDialogueTurnIds(conversationId, CompactTurnIndexer.indexDialogueTurns(path))
        val turns = CompactTurnIndexer.indexDialogueTurns(loadStubPath(conversationId))
            .filter { it.isReadyToIntegrate() && !it.turnId.startsWith("pending_") }
        if (turns.isEmpty()) return CompactResult.NotNeeded
        turns.forEachIndexed { index, turn ->
            onProgress(
                CompactBatchProgress(
                    completed = index,
                    total = turns.size,
                    kind = CompactProgressKind.OPTIMIZE,
                ),
            )
            integrateTurn(conversationId, turn.turnId, config, force = true)
            onProgress(
                CompactBatchProgress(
                    completed = index + 1,
                    total = turns.size,
                    kind = CompactProgressKind.OPTIMIZE,
                ),
            )
        }
        return CompactResult.Created("optimize_$conversationId")
    }

    private suspend fun integrateTurn(
        conversationId: String,
        turnId: String,
        config: AutomaticCompactConfig,
        force: Boolean,
    ) {
        val key = "${conversationId}:${turnId}"
        val mutex = turnLocks.getOrPut(key) { Mutex() }
        mutex.withLock {
            val latestStub = CompactTurnIndexer.indexDialogueTurns(loadStubPath(conversationId))
                .firstOrNull { it.turnId == turnId }
                ?: return
            if (!force && !latestStub.integrateText.isNullOrBlank()) return
            val latest = hydrateTurns(listOf(latestStub)).first()
            val body = runIntegrateLlm(latest, config) ?: return
            if (conversations.getConversation(conversationId) == null) return
            conversations.setIntegrateText(latest.turnId, body)
            detailStore.put(
                toolName = TimeBandPolicy.TURN_INTEGRATION_TOOL,
                summary = body.take(200),
                payload = formatTurnOriginals(latest),
                preferredId = latest.turnId,
            )
        }
    }

    private suspend fun ensureDialogueTurnIds(conversationId: String, turns: List<CompactTurn>) {
        for (turn in turns) {
            if (!turn.isReadyToIntegrate()) continue
            val id = if (turn.turnId.startsWith("pending_")) {
                "turn_${UUID.randomUUID()}"
            } else {
                turn.turnId
            }
            conversations.assignTurnIdIfAbsent(turn.anchorId, id)
        }
    }

    private suspend fun runIntegrateLlm(
        turn: CompactTurn,
        config: AutomaticCompactConfig,
    ): String? {
        val userBody = buildString {
            append(BuiltInPrompts.CONTEXT_INTEGRATE_USER)
            append("\n\n[Current turn originals]\n")
            append(formatTurnOriginals(turn, includeHeading = false))
        }
        val prompt = listOf(
            ChatMessage(
                text = userBody,
                participant = Participant.USER,
                status = MessageStatus.SUCCESS,
            ),
        )
        val provider = config.provider ?: return null
        val modelId = ModelId.parse(providers.canonicalModelId(config.request.model)).modelName
        val providerConfig = ProviderConfig(
            apiKey = config.apiKey,
            modelId = modelId,
            systemPrompt = BuiltInPrompts.CONTEXT_INTEGRATE_SYSTEM,
            maxContextWindow = config.generationConfig.maxContextWindow.coerceAtLeast(4096),
            thinkingEnabled = false,
            baseUrl = config.baseUrl,
            maxTokens = compactMaxOutputTokens(config.freezeThreshold),
        )
        var text = ""
        var error: String? = null
        suspend fun collect() {
            provider.generateResponse(prompt, providerConfig).collect { event ->
                when (event) {
                    is StreamEvent.TextChunk -> text += event.text
                    is StreamEvent.Error -> error = event.message
                    else -> Unit
                }
            }
        }
        try {
            if (config.providerName == Constants.PROVIDER_LOCAL) {
                LocalModelSerializer.mutex.withLock {
                    withContext(Dispatchers.IO) { collect() }
                }
            } else {
                collect()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            DebugLog.e("IntegrationEngine", "Turn integrate failed", e)
            return null
        }
        if (error != null) {
            DebugLog.e("IntegrationEngine", "Turn integrate error: $error")
            return null
        }
        return text.trim().takeIf { it.isNotBlank() }
    }

    private suspend fun hydrateTurns(turns: List<CompactTurn>): List<CompactTurn> =
        hydrateCompactTurns(
            turns = turns,
            loadEntities = conversations::getMessagesByIds,
            includeStoredTranscriptions = false,
        )

    private suspend fun loadStubPath(conversationId: String): List<ChatMessage> {
        val graph = conversations.getCompactMessageGraph(conversationId)
        val selectedChildren = conversations.restoreBranchSelections(conversationId)
        return assembleCompactProviderPath(graph, selectedChildren)
    }

}
