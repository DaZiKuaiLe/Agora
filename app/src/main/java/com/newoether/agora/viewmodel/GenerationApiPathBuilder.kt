package com.newoether.agora.viewmodel

import android.content.Context
import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.util.ContextTokenEstimator
import com.newoether.agora.api.util.applyTimeBandProjection
import com.newoether.agora.api.util.projectGenerationStatusesForApi
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.tool.DetailArchive
import com.newoether.agora.tool.DetailStore
import com.newoether.agora.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class GenerationApiPathRequest(
    val parentId: String?,
    val conversationId: String,
    val config: GenerationConfig,
    val context: GenerationContext,
    val loadedMessages: List<MessageEntity>? = null,
)

internal data class GenerationApiPath(
    val messages: List<ChatMessage>,
    val providerConfig: ProviderConfig,
)

internal fun interface GenerationToolDefinitionSource {
    fun definitions(context: GenerationContext): List<ToolDefinition>
}

/**
 * Builds the immutable Provider request path from the compact identity graph, hydrating only
 * time-band survivors (or a caller-supplied in-memory graph used by tests).
 *
 * It may read Room when the caller has not supplied a snapshot, but it performs no writes and has
 * no runtime, Provider, tool-execution, continuation or finalization authority.
 */
internal class GenerationApiPathBuilder(
    private val conversations: ConversationRepository,
    private val toolDefinitions: GenerationToolDefinitionSource,
    private val detailStore: DetailStore,
    private val appContext: Context,
) {
    suspend fun build(request: GenerationApiPathRequest): GenerationApiPath =
        withContext(Dispatchers.Default) {
            val currentPath = if (request.loadedMessages != null) {
                projectLoadedMessages(request)
            } else {
                projectGraphPath(request)
            }
            val config = request.config
            val definitions = toolDefinitions.definitions(request.context)
            val fixedTokenCost = ContextTokenEstimator.estimateFixed(
                systemPrompt = config.effectiveSystemPrompt,
                tools = definitions,
                initialUserPrompt = config.initialUserPrompt,
            )
            GenerationApiPath(
                messages = currentPath,
                providerConfig = ProviderConfig(
                    apiKey = config.apiKey,
                    modelId = config.modelId,
                    includeImages = !request.context.imageTranscriptionEnabled,
                    systemPrompt = config.effectiveSystemPrompt,
                    maxContextWindow = (config.maxContextWindow - fixedTokenCost).coerceAtLeast(1),
                    codeExecutionEnabled = config.codeExecutionEnabled,
                    googleSearchEnabled = config.googleSearchEnabled,
                    thinkingEnabled = config.thinkingEnabled,
                    thinkingLevel = config.thinkingLevel,
                    thinkingBudgetEnabled = config.thinkingBudgetEnabled,
                    thinkingBudgetTokens = config.thinkingBudgetTokens,
                    openAiServiceTier = config.openAiServiceTier,
                    responsesApiEnabled = config.responsesApiEnabled,
                    openAiWebSearchEnabled = config.openAiWebSearchEnabled,
                    baseUrl = config.baseUrl,
                    tools = definitions,
                    userPrepend = config.userPrepend,
                    userPostpend = config.userPostpend,
                    temperature = config.temperature,
                    maxTokens = config.maxTokens,
                    topP = config.topP,
                    frequencyPenalty = config.frequencyPenalty,
                    presencePenalty = config.presencePenalty,
                ),
            )
        }

    private fun projectLoadedMessages(request: GenerationApiPathRequest): List<ChatMessage> {
        val dbMessages = request.loadedMessages.orEmpty()
        val messagesById = dbMessages.associateBy { it.id }
        val hasSummaryBatches = dbMessages.any {
            !it.compactBatchId.isNullOrBlank() && !it.compactSummary.isNullOrBlank()
        }
        val pathEntities = mutableListOf<MessageEntity>()
        var currentId: String? = request.parentId
        while (currentId != null) {
            val message = messagesById[currentId] ?: break
            pathEntities.add(0, message)
            if (
                !hasSummaryBatches &&
                message.id.startsWith(Constants.COMPACT_MSG_PREFIX) &&
                message.status == MessageStatus.SUCCESS
            ) {
                break
            }
            currentId = message.parentId
        }
        val expanded = ApiPathAssembler.assemble(pathEntities, dbMessages)
        return DetailArchive.applyStubs(
            messages = projectGenerationStatusesForApi(
                projectProviderMessages(
                    entities = expanded,
                    includeStoredTranscriptions = request.context.imageTranscriptionEnabled,
                ),
            ),
            store = detailStore,
            context = appContext,
        )
    }

    private suspend fun projectGraphPath(request: GenerationApiPathRequest): List<ChatMessage> {
        val graph = conversations.getCompactMessageGraph(request.conversationId)
        val graphById = graph.associateBy { it.id }
        val hasSummaryBatches = graph.any { !it.compactBatchId.isNullOrBlank() }
        val ancestorIds = generationAncestorIds(
            graphById = graphById,
            startId = request.parentId,
            stopAtSuccessfulCompact = !hasSummaryBatches,
        )
        val protocolIds = generationProtocolIds(graph, ancestorIds)
        val includeStoredTranscriptions = request.context.imageTranscriptionEnabled
        if (!hasSummaryBatches) {
            val hydrationIds = (ancestorIds + protocolIds).distinct()
            val hydrated = conversations.getMessagesByIds(hydrationIds).associateBy { it.id }
            val ancestorEntities = ancestorIds.mapNotNull { hydrated[it] }
            val expanded = ApiPathAssembler.assemble(ancestorEntities, hydrated.values.toList())
            return DetailArchive.applyStubs(
                messages = projectGenerationStatusesForApi(
                    projectProviderMessages(expanded, includeStoredTranscriptions),
                ),
                store = detailStore,
                context = appContext,
            )
        }
        val stubById = (ancestorIds + protocolIds).distinct()
            .mapNotNull { id -> graphById[id]?.toStubMessageEntity() }
            .associateBy { it.id }
        val expandedStubs = ApiPathAssembler.assemble(
            ancestorIds.mapNotNull { stubById[it] },
            stubById.values.toList(),
        )
        var stubPath = projectGenerationStatusesForApi(
            projectProviderMessages(expandedStubs, includeStoredTranscriptions = false),
        )
        val digest = loadNewestPathDigest(stubPath, graphById, conversations::getCompactDigest)
        val digestAnchor = newestBatchedDigestAnchorId(stubPath) { id ->
            graphById[id]?.hasCompactDigest == true
        }?.first
        stubPath = pathWithDigestBody(stubPath, digest, digestAnchor)
        val retainTurns = request.config.contextRetainTurns
        val projected = applyTimeBandProjection(stubPath, retainTurns)
        val hydrated = hydrateMessagesByIds(
            ids = timeBandSurvivorIds(stubPath, retainTurns),
            loadEntities = conversations::getMessagesByIds,
            includeStoredTranscriptions = includeStoredTranscriptions,
        )
        return DetailArchive.applyStubs(
            messages = mergeHydratedMessages(projected, hydrated),
            store = detailStore,
            context = appContext,
        )
    }
}
