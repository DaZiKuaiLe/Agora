package com.newoether.agora.viewmodel

import android.content.Context
import com.newoether.agora.data.BuiltInPrompts
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID

internal data class StandardCompactLaunch(
    val messageId: String,
    val job: Job,
)

/**
 * Summary-batch Compact via [BatchCompactEngine] (no capsule message).
 * [StandardCompactLaunch.messageId] is the parent used to continue generation after auto-compact.
 */
internal class ConversationCompactController(
    private val batchEngine: BatchCompactEngine,
    private val requestBuilder: GenerationRequestBuilder?,
    private val onCompactStarted: (conversationId: String, messageId: String) -> Unit = { _, _ -> },
    private val onBatchCompactingChanged: (conversationId: String, compacting: Boolean) -> Unit =
        { _, _ -> },
    private val onBatchCompactProgress: (conversationId: String, progress: CompactBatchProgress) -> Unit =
        { _, _ -> },
    private val onDigestIntegrity: (conversationId: String, issues: List<DigestIntegrityIssue>) -> Unit =
        { _, _ -> },
    private val appContext: Context? = null,
) {
    suspend fun automaticNeeded(
        conversationId: String,
        config: AutomaticCompactConfig,
    ): Boolean = batchEngine.automaticNeeded(conversationId, config)

    suspend fun digestKnowledgeStale(conversationId: String): Boolean =
        batchEngine.digestKnowledgeStale(conversationId)

    suspend fun compactFormatStale(conversationId: String): Boolean =
        batchEngine.compactFormatStale(conversationId)

    suspend fun archiveFullRecompact(
        conversationId: String,
        config: AutomaticCompactConfig,
        onProgress: ((CompactBatchProgress) -> Unit)? = null,
    ): CompactResult = batchEngine.ensureFormalBatches(
        conversationId = conversationId,
        config = config,
        fullRecompact = true,
        onProgress = onProgress,
        appContext = appContext,
    ).alsoReportIntegrity(conversationId)

    suspend fun startFullRecompactBeforeSend(
        conversationId: String,
        config: AutomaticCompactConfig,
    ): CompactResult {
        onBatchCompactingChanged(conversationId, true)
        return try {
            val result = batchEngine.ensureFormalBatches(
                conversationId = conversationId,
                config = config,
                fullRecompact = true,
                onProgress = { onBatchCompactProgress(conversationId, it) },
                appContext = appContext,
            )
            if (result is CompactResult.Created) {
                onCompactStarted(conversationId, result.messageId)
            }
            result.alsoReportIntegrity(conversationId)
        } finally {
            onBatchCompactingChanged(conversationId, false)
        }
    }

    suspend fun refreshConversationDigest(
        conversationId: String,
        config: AutomaticCompactConfig,
    ): CompactResult {
        onBatchCompactingChanged(conversationId, true)
        return try {
            val result = batchEngine.refreshConversationDigest(conversationId, config)
            if (result is CompactResult.Created) {
                onCompactStarted(conversationId, result.messageId)
            }
            result.alsoReportIntegrity(conversationId)
        } finally {
            onBatchCompactingChanged(conversationId, false)
        }
    }

    suspend fun startAutomaticBeforeSend(
        conversationId: String,
        config: AutomaticCompactConfig,
        @Suppress("UNUSED_PARAMETER") state: ConversationGenerationState,
    ): CompactResult {
        onBatchCompactingChanged(conversationId, true)
        return try {
            val result = batchEngine.ensureFormalBatches(
                conversationId = conversationId,
                config = config,
                onProgress = { onBatchCompactProgress(conversationId, it) },
            )
            if (result is CompactResult.Created) {
                onCompactStarted(conversationId, result.messageId)
            }
            result.alsoReportIntegrity(conversationId)
        } finally {
            onBatchCompactingChanged(conversationId, false)
        }
    }

    suspend fun startAutomaticStandard(
        conversationId: String,
        config: AutomaticCompactConfig,
        state: ConversationGenerationState,
        continuationParentId: String,
    ): StandardCompactLaunch? {
        if (!batchEngine.automaticNeeded(conversationId, config)) return null
        val job = state.scope.launch {
            onBatchCompactingChanged(conversationId, true)
            try {
                batchEngine.ensureFormalBatches(
                    conversationId = conversationId,
                    config = config,
                    onProgress = { onBatchCompactProgress(conversationId, it) },
                ).alsoReportIntegrity(conversationId)
            } finally {
                onBatchCompactingChanged(conversationId, false)
            }
        }
        onCompactStarted(conversationId, continuationParentId)
        return StandardCompactLaunch(continuationParentId, job)
    }

    suspend fun manual(
        conversationId: String,
        request: CompactRequest,
        @Suppress("UNUSED_PARAMETER") state: ConversationGenerationState,
    ): CompactResult {
        if (request.model.isBlank()) return CompactResult.Failed(CompactFailureReason.SELECT_MODEL)
        if (request.retainLogicalMessages !in 2..32) {
            return CompactResult.Failed(CompactFailureReason.INVALID_RETAIN_COUNT)
        }
        val snapshotRunId = "compact_preflight_${UUID.randomUUID()}"
        val snapshot = try {
            val builder = requestBuilder
                ?: return CompactResult.Failed(CompactFailureReason.SETUP_UNAVAILABLE)
            builder.captureAdmissionSnapshot(
                conversationId = conversationId,
                runId = snapshotRunId,
                modelId = request.model,
            )
        } catch (_: Exception) {
            return CompactResult.Failed(CompactFailureReason.SETUP_FAILED)
        }
        val config = snapshot.automaticCompact.copy(
            enabled = true,
            request = request.copy(prompt = BuiltInPrompts.CONTEXT_COMPACT_SYSTEM),
        )
        if (!config.configured || config.provider == null) {
            return CompactResult.Failed(CompactFailureReason.SETUP_UNAVAILABLE)
        }
        onBatchCompactingChanged(conversationId, true)
        return try {
            // Manual: incremental formal compress. Full recompact only when requested
            // (legacy capsule sessions / explicit recompact-all).
            val result = batchEngine.ensureFormalBatches(
                conversationId = conversationId,
                config = config,
                fullRecompact = request.fullRecompact,
                onProgress = { onBatchCompactProgress(conversationId, it) },
                appContext = if (request.fullRecompact) appContext else null,
            )
            if (result is CompactResult.Created) {
                onCompactStarted(conversationId, result.messageId)
            }
            result.alsoReportIntegrity(conversationId)
        } finally {
            onBatchCompactingChanged(conversationId, false)
        }
    }

    fun schedulePrecompress(
        conversationId: String,
        config: AutomaticCompactConfig,
        state: ConversationGenerationState,
    ) {
        if (!config.enabled) return
        val job = state.scope.launch {
            runCatching {
                batchEngine.precompressFullBatches(conversationId, config)
            }
        }
        batchEngine.trackPrecompress(conversationId, job)
    }

    private fun CompactResult.alsoReportIntegrity(conversationId: String): CompactResult {
        val issues = (this as? CompactResult.Created)?.integrityIssues.orEmpty()
        if (issues.isNotEmpty()) onDigestIntegrity(conversationId, issues)
        return this
    }
}
