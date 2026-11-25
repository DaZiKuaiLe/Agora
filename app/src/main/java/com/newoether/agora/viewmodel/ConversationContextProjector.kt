package com.newoether.agora.viewmodel

import android.content.Context
import com.newoether.agora.api.util.ContextTokenEstimator
import com.newoether.agora.api.util.ContextWindowUsage
import com.newoether.agora.api.util.contextWindowRetainedMessageIds
import com.newoether.agora.api.util.contextWindowUsage
import com.newoether.agora.api.util.contextMessagesWithinTurnCap
import com.newoether.agora.api.util.omitSuppressedDialogueTurns
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.tool.CompactTurnIndexer
import com.newoether.agora.tool.DetailArchive
import com.newoether.agora.tool.DetailStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class ConversationContextProjection(
    val usage: ContextWindowUsage,
    val retainedMessageIds: Set<String>,
    val fixedTokenCost: Int = 0,
    val lastTurnId: String? = null,
    val unbatchedPrefixTokens: Int = 0,
)

/**
 * Recent-turn window shown in context detail, including turns unchecked for the next send.
 * [sentTokenCount] matches the provider payload, which omits those unchecked turns.
 *
 * [exportSourceMessages] is the pre-compression path used only when exporting, so batch
 * `origin` arrays can be rebuilt from durable turns.
 */
internal data class ConversationContextDetail(
    val systemPrompt: String,
    val messages: List<ChatMessage>,
    val usage: ContextWindowUsage,
    val fixedTokenCost: Int,
    val messageTokenBudget: Int,
    val exportSourceMessages: List<ChatMessage> = emptyList(),
    val dialogueTurnCount: Int = 0,
    val originalTokenCount: Int = 0,
    val sentTokenCount: Int = 0,
    val unbatchedPrefixTokens: Int = 0,
    val ledgerCursor: String = "",
)

/** Builds UI context accounting from the same durable/provider projection used by generation. */
internal class ConversationContextProjector(
    private val conversations: ConversationRepository,
    private val requestBuilder: GenerationRequestBuilder,
    private val generationManager: () -> GenerationManager,
    @Suppress("UNUSED_PARAMETER")
    private val toBranchMessage: (MessageEntity) -> ChatMessage,
    private val detailStore: () -> DetailStore,
    private val appContext: Context,
    private val newChatSystemPromptId: () -> String? = { null },
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    suspend fun project(
        conversationId: String?,
        selectedModelId: String,
        tokenBudget: Int,
        computeRetainedIds: Boolean = true,
    ): ConversationContextProjection = withContext(computeDispatcher) {
        val built = buildProviderContext(conversationId, selectedModelId, tokenBudget)
        ConversationContextProjection(
            usage = built.usage,
            retainedMessageIds = if (computeRetainedIds) {
                contextWindowRetainedMessageIds(
                    messages = built.contextMessages,
                    tokenBudget = tokenBudget,
                    fixedTokenCost = built.fixedTokenCost,
                )
            } else {
                emptySet()
            },
            fixedTokenCost = built.fixedTokenCost,
            lastTurnId = CompactTurnIndexer.finishedDialogueTurns(built.exportSourceMessages)
                .lastOrNull()
                ?.turnId,
            unbatchedPrefixTokens = built.unbatchedPrefixTokens,
        )
    }

    suspend fun detail(
        conversationId: String?,
        selectedModelId: String,
        tokenBudget: Int,
    ): ConversationContextDetail = withContext(computeDispatcher) {
        val built = buildProviderContext(
            conversationId,
            selectedModelId,
            tokenBudget,
            guardRetainedBodies = true,
        )
        val messageBudget = (tokenBudget - built.fixedTokenCost).coerceAtLeast(1)
        val prepared = contextMessagesWithinTurnCap(built.contextMessages, messageBudget)
        val sentMessages = omitSuppressedDialogueTurns(prepared)
        val originalTokens = (
            built.originalTokenCount.toLong() +
                built.fixedTokenCost.coerceAtLeast(0).toLong()
            ).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val sentTokens = (
            ContextTokenEstimator.estimate(sentMessages).toLong() +
                built.fixedTokenCost.coerceAtLeast(0).toLong()
            ).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        ConversationContextDetail(
            systemPrompt = built.systemPrompt,
            messages = prepared,
            usage = built.usage,
            fixedTokenCost = built.fixedTokenCost,
            messageTokenBudget = messageBudget,
            exportSourceMessages = built.exportSourceMessages,
            dialogueTurnCount = CompactTurnIndexer.indexDialogueTurns(built.exportSourceMessages).size,
            originalTokenCount = originalTokens,
            sentTokenCount = sentTokens,
            unbatchedPrefixTokens = built.unbatchedPrefixTokens,
            ledgerCursor = sendLedgerCursor(built.exportSourceMessages),
        )
    }

    private data class BuiltContext(
        val systemPrompt: String,
        val contextMessages: List<ChatMessage>,
        val exportSourceMessages: List<ChatMessage>,
        val originalTokenCount: Int,
        val usage: ContextWindowUsage,
        val fixedTokenCost: Int,
        val unbatchedPrefixTokens: Int,
    )

    private suspend fun buildProviderContext(
        conversationId: String?,
        selectedModelId: String,
        tokenBudget: Int,
        guardRetainedBodies: Boolean = false,
    ): BuiltContext {
        val effectiveConversationId = conversationId ?: CONTEXT_PREVIEW_CONVERSATION_ID
        val graph = conversationId?.let {
            conversations.getCompactMessageGraph(it)
        }.orEmpty()
        val selections = conversationId?.let {
            conversations.restoreBranchSelections(it)
        }.orEmpty()
        val stubPath = assembleCompactProviderPath(graph, selections)
        val graphById = graph.associateBy { it.id }
        val digest = loadNewestPathDigest(stubPath, graphById, conversations::getCompactDigest)
        val digestAnchor = newestBatchedDigestAnchorId(stubPath) { id ->
            graphById[id]?.hasCompactDigest == true
        }?.first
        val pathForBand = pathWithDigestBody(stubPath, digest, digestAnchor)

        val snapshot = selectedModelId.takeIf(String::isNotBlank)?.let { modelId ->
            try {
                requestBuilder.captureContextProjectionSnapshot(
                    conversationId = effectiveConversationId,
                    modelId = modelId,
                    systemPromptIdOverride = if (conversationId == null) {
                        newChatSystemPromptId()
                    } else {
                        null
                    },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
        val includeStoredTranscriptions = snapshot?.context?.imageTranscriptionEnabled ?: true
        val includeImages = snapshot?.let { !it.context.imageTranscriptionEnabled } ?: true
        val retainTurns = snapshot?.config?.contextRetainTurns ?: 8
        val projectedStub = com.newoether.agora.api.util.applyTimeBandProjection(
            pathForBand,
            retainTurns,
        )
        val survivorIds = timeBandSurvivorIds(pathForBand, retainTurns)
        if (guardRetainedBodies) {
            val characters = conversations.sumMessageBodyChars(survivorIds)
            if (contextDetailRetainedBodyTooLarge(characters)) {
                throw ContextDetailBodyTooLargeException(characters)
            }
        }
        val hydratedById = hydrateProviderMessages(survivorIds, includeStoredTranscriptions)
        val durableProviderMessages = mergeHydratedMessages(projectedStub, hydratedById)
        val contextMessages = snapshot?.let {
            projectGenerationInputMessages(
                messages = durableProviderMessages,
                includeImages = includeImages,
                userPrepend = it.config.userPrepend,
                userPostpend = it.config.userPostpend,
                askMode = it.context.askMode,
            )
        } ?: durableProviderMessages
        val stubbedMessages = DetailArchive.applyStubs(
            messages = contextMessages,
            store = detailStore(),
            context = appContext,
        )
        val fixedTokenCost = snapshot?.let {
            generationManager().fixedContextTokenCost(it.config, it.context)
        } ?: 0
        val usage = contextWindowUsage(
            messages = stubbedMessages,
            tokenBudget = tokenBudget,
            fixedTokenCost = fixedTokenCost,
        )
        val prefixIds = uncompressedCompactThresholdMessages(pathForBand, retainTurns).map { it.id }
        val unbatchedPrefixTokens = estimateMessageIds(prefixIds, includeStoredTranscriptions)
        val originalTokenCount = estimateMessageIds(stubPath.map { it.id }, includeStoredTranscriptions)
        return BuiltContext(
            systemPrompt = snapshot?.config?.effectiveSystemPrompt.orEmpty(),
            contextMessages = stubbedMessages,
            // Turn index / export notes use the selected-path identity; bodies stay on disk.
            exportSourceMessages = pathForBand,
            originalTokenCount = originalTokenCount,
            usage = usage,
            fixedTokenCost = fixedTokenCost,
            unbatchedPrefixTokens = unbatchedPrefixTokens,
        )
    }

    private suspend fun hydrateProviderMessages(
        ids: List<String>,
        includeStoredTranscriptions: Boolean,
    ): Map<String, ChatMessage> {
        if (ids.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, ChatMessage>(ids.size)
        ids.distinct().chunked(CONTEXT_BODY_ID_CHUNK).forEach { chunk ->
            val projected = projectHydratedEntities(
                conversations.getMessagesByIds(chunk),
                includeStoredTranscriptions,
            )
            projected.forEach { message -> out[message.id] = message }
        }
        return out
    }

    private suspend fun estimateMessageIds(
        ids: List<String>,
        includeStoredTranscriptions: Boolean,
    ): Int {
        if (ids.isEmpty()) return 0
        var total = 0L
        ids.distinct().chunked(CONTEXT_ESTIMATE_ID_CHUNK).forEach { chunk ->
            val projected = projectHydratedEntities(
                conversations.getMessagesByIds(chunk),
                includeStoredTranscriptions,
            )
            total = (total + ContextTokenEstimator.estimate(projected).toLong())
                .coerceAtMost(Int.MAX_VALUE.toLong())
        }
        return total.toInt()
    }

    private companion object {
        const val CONTEXT_PREVIEW_CONVERSATION_ID = "context-preview-conversation"
        const val CONTEXT_BODY_ID_CHUNK = 8
        const val CONTEXT_ESTIMATE_ID_CHUNK = 4
    }
}

/**
 * Context detail keeps every hydrated survivor in one map. Past this size that map, plus the
 * decoded copy, has filled the process heap. The check uses on-disk character lengths so the
 * refusal does not load the bodies and does not write the conversation.
 */
internal const val CONTEXT_DETAIL_MAX_RETAINED_CHARS = 16_000_000L

internal fun contextDetailRetainedBodyTooLarge(characters: Long): Boolean =
    characters > CONTEXT_DETAIL_MAX_RETAINED_CHARS

internal class ContextDetailBodyTooLargeException(
    val characters: Long,
) : Exception("retained context body is $characters characters")
