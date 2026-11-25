package com.newoether.agora.viewmodel

import com.newoether.agora.api.GenerationError
import com.newoether.agora.api.util.contextWindowUsage
import com.newoether.agora.api.util.projectGenerationStatusesForApi
import com.newoether.agora.api.util.splitContextForCompactRetention
import com.newoether.agora.api.util.stripEmptyTurns
import com.newoether.agora.api.util.validateToolMessages
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.ContextBudget
import com.newoether.agora.model.Participant
import com.newoether.agora.model.isContextCompact
import com.newoether.agora.model.isSuccessfulContextCompact
import com.newoether.agora.util.Constants

data class CompactRequest(
    val model: String,
    val prompt: String,
    val retainLogicalMessages: Int,
    val replaceMessageId: String? = null,
    /** True for legacy capsule migration / explicit full recompact; false for incremental. */
    val fullRecompact: Boolean = false,
)

/** Provider-equivalent split input without role coalescing away durable graph ids. */
internal fun compactSplitMessages(messages: List<ChatMessage>): List<ChatMessage> =
    stripEmptyTurns(
        validateToolMessages(
            projectGenerationStatusesForApi(messages.distinctBy(ChatMessage::id))
        )
    )

internal fun buildPersistedCompactText(
    summary: String,
    retainedMessages: List<ChatMessage>,
): String = buildString {
    append(summary.trim())
    if (retainedMessages.isEmpty()) return@buildString
    append("\n\n--- Recent messages (verbatim) ---")
    retainedMessages.forEach { message ->
        append("\n\n")
        when {
            message.id.startsWith(Constants.TOOL_MSG_PREFIX) -> {
                val calls = message.segments.orEmpty().filter { it.type == "tool" }
                if (calls.isEmpty()) {
                    append("[Assistant tool request]\n")
                    append(message.text)
                } else {
                    calls.forEachIndexed { index, call ->
                        if (index > 0) append("\n\n")
                        append("[Assistant tool request: ")
                        append(call.toolName?.takeIf(String::isNotBlank) ?: "unknown")
                        append("]\n")
                        append(call.toolArgs.orEmpty())
                    }
                }
            }
            message.id.startsWith(Constants.RESULT_MSG_PREFIX) -> {
                val results = message.segments.orEmpty().filter { it.type == "tool" }
                if (results.isEmpty()) {
                    append("[Tool result]\n")
                    append(message.text)
                } else {
                    results.forEachIndexed { index, result ->
                        if (index > 0) append("\n\n")
                        append("[Tool result: ")
                        append(result.toolName?.takeIf(String::isNotBlank) ?: "unknown")
                        append("]\n")
                        append(result.toolResult.orEmpty())
                    }
                }
            }
            message.participant == Participant.USER -> {
                append("[User]\n")
                append(message.text)
            }
            else -> {
                append("[Assistant]\n")
                append(message.text)
            }
        }
        if (message.images.isNotEmpty()) {
            append("\n[Attached images: ")
            append(message.images.size)
            append(']')
        }
    }
}

/** Compact LLM output cap is the freeze threshold (default 256K), not the working-model maxTokens. */
internal fun compactMaxOutputTokens(freezeThreshold: Int): Int =
    freezeThreshold.takeIf { it > 0 } ?: ContextBudget.DEFAULT_FREEZE_THRESHOLD

/**
 * Output cap and context-window overflow both mean this slice does not fit: drop the draft and
 * retry with the first half of the candidate turns.
 */
internal fun compactLlmShouldShrinkSlice(error: GenerationError): Boolean {
    if (error is GenerationError.OutputTruncated) return true
    val haystack = when (error) {
        is GenerationError.Api ->
            listOfNotNull(error.code, error.type, error.message).joinToString(" ")
        is GenerationError.Network -> error.message
        is GenerationError.RequestFormat -> error.details
        is GenerationError.LocalModel -> error.message
        is GenerationError.Unknown -> error.cause.message.orEmpty()
        else -> ""
    }.lowercase()
    if (haystack.isBlank()) return false
    return COMPACT_WINDOW_OVERFLOW_MARKERS.any { it in haystack }
}

private val COMPACT_WINDOW_OVERFLOW_MARKERS = listOf(
    "maximum context length",
    "max context length",
    "context_length",
    "context length is",
    "exceeds the context",
    "exceeds the maximum context",
    "prompt is too long",
    "input is too long",
    "input token count exceeds",
    "reduce the length of the messages",
    "reduce the length of the prompt",
    "please reduce the length",
    "too many tokens",
)

enum class CompactFailureReason {
    SELECT_MODEL,
    EMPTY_PROMPT,
    INVALID_RETAIN_COUNT,
    SETUP_UNAVAILABLE,
    SETUP_FAILED,
    NOT_READY_TO_RECOMPACT,
    BOUNDARY_DISAPPEARED,
    GENERATION_BUSY,
    GENERATION_NOT_STARTED,
    MESSAGE_DISAPPEARED,
    OPEN_CONVERSATION,
    TURN_TOO_LARGE,
    GENERIC,
}

sealed interface CompactResult {
    data class Created(
        val messageId: String,
        val integrityIssues: List<DigestIntegrityIssue> = emptyList(),
    ) : CompactResult
    data class Stopped(val messageId: String) : CompactResult
    data object NotNeeded : CompactResult
    data class Failed(
        val reason: CompactFailureReason,
        val externalDetail: String? = null,
        val messageId: String? = null,
    ) : CompactResult
}

/** Narrow operation port used by the application-level Compact effect executor. */
internal fun interface ContextCompactOperation {
    suspend fun automaticNeeded(
        conversationId: String,
        config: AutomaticCompactConfig,
    ): Boolean
}

internal fun automaticCompactNeeded(
    entities: List<MessageEntity>,
    selectedChildren: Map<String?, String>,
    contextLimit: Int,
    retainLogicalMessages: Int,
    includeStoredTranscriptions: Boolean = false,
    fixedTokenCost: Int = 0,
    userPrepend: String? = null,
    userPostpend: String? = null,
): Boolean {
    val selectedPath = ConversationUiState.resolvePath(
        allMessages = entities.map { it.toUiChatMessage { text -> text } },
        streamingMsg = null,
        selectedChildren = selectedChildren,
    )
    val entitiesById = entities.associateBy(MessageEntity::id)
    return automaticCompactNeeded(
        path = ApiPathAssembler.assemble(
            selectedPath.mapNotNull { entitiesById[it.id] },
            entities,
        ).let { projectProviderMessages(it, includeStoredTranscriptions) },
        contextLimit = contextLimit,
        retainLogicalMessages = retainLogicalMessages,
        fixedTokenCost = fixedTokenCost,
        userPrepend = userPrepend,
        userPostpend = userPostpend,
        // Transcription-enabled models receive descriptions instead of raw images at dispatch;
        // the admission estimate must match.
        includeImages = !includeStoredTranscriptions,
    )
}

internal fun automaticCompactNeeded(
    path: List<ChatMessage>,
    contextLimit: Int,
    retainLogicalMessages: Int,
    fixedTokenCost: Int = 0,
    userPrepend: String? = null,
    userPostpend: String? = null,
    includeImages: Boolean = true,
): Boolean {
    if (path.isEmpty() || retainLogicalMessages < 0) return false
    val semanticPath = path.filterNot { it.isContextCompact() && !it.isSuccessfulContextCompact() }
    val nearest = semanticPath.indexOfLast(ChatMessage::isSuccessfulContextCompact)
    val compactablePath = compactSplitMessages(
        semanticPath.drop(nearest.coerceAtLeast(-1) + 1),
    )
    val split = splitContextForCompactRetention(compactablePath, retainLogicalMessages)
    return split.prefix.isNotEmpty() &&
        contextWindowUsage(
            projectGenerationInputMessages(
                messages = semanticPath,
                includeImages = includeImages,
                userPrepend = userPrepend,
                userPostpend = userPostpend,
            ),
            contextLimit.coerceAtLeast(1),
            fixedTokenCost = fixedTokenCost,
        ).estimatedTokenCount >=
        contextLimit.coerceAtLeast(1)
}

/** Non-destructive context compaction. Original messages remain in the graph. */
internal class ContextCompactor(
    private val batchEngine: BatchCompactEngine,
) : ContextCompactOperation {
    override suspend fun automaticNeeded(
        conversationId: String,
        config: AutomaticCompactConfig,
    ): Boolean = batchEngine.automaticNeeded(conversationId, config)
}
