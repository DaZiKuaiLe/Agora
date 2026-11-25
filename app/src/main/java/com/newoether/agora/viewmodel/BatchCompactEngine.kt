package com.newoether.agora.viewmodel

import com.newoether.agora.api.GenerationError
import com.newoether.agora.api.LocalModelSerializer
import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.StreamEvent
import com.newoether.agora.api.util.PathDigest
import com.newoether.agora.api.util.contextWindowUsage
import com.newoether.agora.api.util.resolvePathDigest
import com.newoether.agora.data.BuiltInPrompts
import com.newoether.agora.data.CompactFormat
import com.newoether.agora.data.TimeBandPolicy
import com.newoether.agora.data.encodeResidencyChapters
import com.newoether.agora.data.compactFormatStale as compactFormatRevIsStale
import com.newoether.agora.data.local.CompactMessageGraphRow
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.ModelId
import com.newoether.agora.model.Participant
import com.newoether.agora.model.isContextCompact
import com.newoether.agora.model.isSuccessfulContextCompact
import android.content.Context
import com.newoether.agora.tool.CompactTurn
import com.newoether.agora.tool.CompactTurnIndexer
import com.newoether.agora.tool.DetailArchive
import com.newoether.agora.tool.DetailStore
import com.newoether.agora.util.Constants
import com.newoether.agora.util.CrashReporter
import com.newoether.agora.util.DebugLog
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class CompactBatchProgress(
    val completed: Int,
    val total: Int,
    val kind: CompactProgressKind = CompactProgressKind.BATCH,
)

enum class CompactProgressKind {
    BATCH,
    OPTIMIZE,
}

/**
 * Single-summary compact: fold (optional prior summary + newly evicted originals) into one
 * working-context digest. Covered turns are marked [COVERED_BATCH_ID]; conversation text stays
 * in Room.
 */
internal class BatchCompactEngine(
    private val conversations: ConversationRepository,
    private val detailStore: DetailStore,
    private val providers: ProviderRegistry,
    @Suppress("UNUSED_PARAMETER")
    private val knowledgeSource: DigestKnowledgeSource = DigestKnowledgeSource {
        DigestKnowledgeSnapshot()
    },
) {
    private val conversationLocks = ConcurrentHashMap<String, Mutex>()
    private val precompressJobs = ConcurrentHashMap<String, Job>()
    private var optimizeLogFile: File? = null

    fun cancelPrecompress(conversationId: String) {
        precompressJobs.remove(conversationId)?.cancel()
    }

    fun trackPrecompress(conversationId: String, job: Job) {
        precompressJobs[conversationId]?.cancel()
        precompressJobs[conversationId] = job
        job.invokeOnCompletion { precompressJobs.remove(conversationId, job) }
    }

    suspend fun automaticNeeded(
        conversationId: String,
        config: AutomaticCompactConfig,
    ): Boolean {
        if (!TimeBandPolicy.ARCHIVE_BAND_ENABLED) return false
        if (!config.enabled) return false
        val cache = conversations.getSendTokenCache(conversationId)
        if (cache.isSeated() && cache.retainTurns == config.request.retainLogicalMessages) {
            return automaticCompactNeededFromLedger(cache, config.compactThreshold)
        }
        val loaded = loadProviderPath(conversationId) ?: return false
        val retain = config.request.retainLogicalMessages
        val turns = CompactTurnIndexer.finishedDialogueTurns(loaded.path)
        if (turns.size <= retain) return false
        val uncovered = turns.dropLast(retain).filter { !it.batched }
        if (uncovered.isEmpty()) return false
        val includeImages = !config.generationContext.imageTranscriptionEnabled
        var uncoveredTokens = 0L
        for (turn in uncovered) {
            uncoveredTokens += estimateHydratedTurnTokens(
                turn = turn,
                includeImages = includeImages,
                userPrepend = config.userPrepend,
                userPostpend = config.userPostpend,
                askMode = config.generationContext.askMode,
            )
        }
        val estimated = (uncoveredTokens + config.fixedTokenCost.coerceAtLeast(0).toLong())
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        return estimated >= config.compactThreshold.coerceAtLeast(1)
    }

    suspend fun digestKnowledgeStale(@Suppress("UNUSED_PARAMETER") conversationId: String): Boolean =
        false

    suspend fun refreshConversationDigest(
        @Suppress("UNUSED_PARAMETER") conversationId: String,
        @Suppress("UNUSED_PARAMETER") config: AutomaticCompactConfig,
    ): CompactResult = CompactResult.NotNeeded

    suspend fun precompressFullBatches(
        @Suppress("UNUSED_PARAMETER") conversationId: String,
        @Suppress("UNUSED_PARAMETER") config: AutomaticCompactConfig,
    ): CompactResult = CompactResult.NotNeeded

    suspend fun compactFormatStale(conversationId: String): Boolean {
        if (!TimeBandPolicy.ARCHIVE_BAND_ENABLED) return false
        val conversation = conversations.getConversation(conversationId) ?: return false
        return compactFormatRevIsStale(
            storedRev = conversation.compactFormatRev,
            hasCompactArtifacts = conversations.conversationHasCompactArtifacts(conversationId),
        )
    }

    /**
     * Incremental: compress newly uncovered prefix originals and append a segment.
     * Full recompact: replay automatic compact cadence from originals (threshold-sized
     * slices, each compressed alone). Prefix still under the compact threshold stays original.
     */
    suspend fun ensureFormalBatches(
        conversationId: String,
        config: AutomaticCompactConfig,
        fullRecompact: Boolean = false,
        onProgress: ((CompactBatchProgress) -> Unit)? = null,
        appContext: Context? = null,
    ): CompactResult = withConversationLock(conversationId) {
        if (fullRecompact) openOptimizeLog(appContext)
        try {
        if (!TimeBandPolicy.ARCHIVE_BAND_ENABLED) {
            return@withConversationLock CompactResult.NotNeeded
        }
        cancelPrecompress(conversationId)
        if (!config.configured || config.provider == null) {
            return@withConversationLock CompactResult.Failed(CompactFailureReason.SETUP_UNAVAILABLE)
        }
        if (config.request.model.isBlank()) {
            return@withConversationLock CompactResult.Failed(CompactFailureReason.SELECT_MODEL)
        }
        val retain = config.request.retainLogicalMessages.coerceIn(2, 32)
        ensureTurnIds(conversationId, trace = fullRecompact)
        val initial = loadProviderPath(
            conversationId,
            trace = fullRecompact,
            traceLabel = "loadPath",
        ) ?: return@withConversationLock CompactResult.Failed(CompactFailureReason.OPEN_CONVERSATION)
        val stampFormatRev = fullRecompact || !pathHasCompactArtifacts(initial.path)
        val loaded = initial
        val path = loaded.path
        val turns = CompactTurnIndexer.finishedDialogueTurns(path)
        val prefix = if (turns.size <= retain) emptyList() else turns.dropLast(retain)
        val includeImages = !config.generationContext.imageTranscriptionEnabled
        val totalMessages = path.size.coerceAtLeast(1)
        if (fullRecompact) {
            noteFullCompact(
                "scan.ready messages=$totalMessages turns=${turns.size} prefix=${prefix.size} retain=$retain",
            )
            onProgress?.invoke(
                CompactBatchProgress(
                    completed = 0,
                    total = totalMessages,
                    kind = CompactProgressKind.OPTIMIZE,
                ),
            )
        }
        if (prefix.isEmpty()) {
            if (fullRecompact) {
                commitFinishedCompact(
                    conversations = conversations,
                    conversationId = conversationId,
                    turns = turns,
                    clearExistingBatches = true,
                    coveredTurnIds = emptyList(),
                    batchId = COVERED_BATCH_ID,
                    digestTurnId = null,
                    digest = null,
                    digestRev = 0,
                    formatRev = if (stampFormatRev) CompactFormat.CURRENT_REV else null,
                    summaryTokens = 0,
                    keptOriginalTokens = measureOriginalTokens(turns, config, includeImages),
                )
                retireLegacyCapsules(conversationId, path)
                if (appContext != null) {
                    persistOriginalBand(turns, prefixSize = 0, batched = BooleanArray(0), appContext)
                }
                return@withConversationLock CompactResult.Created(
                    "summary_format_${UUID.randomUUID()}",
                )
            }
            return@withConversationLock CompactResult.NotNeeded
        }
        if (fullRecompact) {
            noteFullCompact("scan.start")
            val tokensByTurn = IntArray(turns.size)
            val batched = BooleanArray(turns.size)
            val compacted = mutableListOf<Int>()
            var chapters = emptyList<String>()
            var compressedMessages = 0
            for (index in prefix.indices) {
                if (index == 0 || index % 10 == 0) {
                    noteFullCompact(
                        "measure.start index=$index/${prefix.size} turn=${turns[index].turnId} msgs=${turns[index].messages.size}",
                    )
                }
                tokensByTurn[index] = estimateHydratedTurnTokens(
                    turn = turns[index],
                    includeImages = includeImages,
                    userPrepend = config.userPrepend,
                    userPostpend = config.userPostpend,
                    askMode = config.generationContext.askMode,
                )
                if (index == 0 || index % 10 == 0) {
                    noteFullCompact("measure.done index=$index tokens=${tokensByTurn[index]}")
                }
                val uncovered = sliceClosedAtPrefix(
                    prefixEnd = index + 1,
                    batched = batched,
                    tokensByTurn = tokensByTurn,
                    compactThreshold = config.compactThreshold,
                    fixedTokenCost = config.fixedTokenCost,
                ) ?: continue
                val estimated = uncovered.fold(0L) { total, turnIndex ->
                    total + tokensByTurn[turnIndex]
                }
                val sliceMessages = uncovered.sumOf { turnIndex -> turns[turnIndex].messages.size }
                noteFullCompact("slice.send turns=${uncovered.size} msgs=$sliceMessages est=$estimated")
                when (
                    val packed = foldMeasuredSlice(
                        turns = turns,
                        indices = uncovered,
                        tokensByTurn = tokensByTurn,
                        oldChapters = chapters,
                        config = config,
                    )
                ) {
                    is CompactPackResult.Chapters -> {
                        chapters = packed.chapters
                        compacted += uncovered
                        compressedMessages += sliceMessages
                        for (done in 0 until index + 1) batched[done] = true
                        onProgress?.invoke(
                            CompactBatchProgress(
                                completed = compressedMessages,
                                total = totalMessages,
                                kind = CompactProgressKind.OPTIMIZE,
                            ),
                        )
                        noteFullCompact("slice.done messages=$compressedMessages/$totalMessages")
                    }
                    CompactPackResult.TurnTooLarge -> {
                        noteFullCompact("turn-too-large")
                        return@withConversationLock CompactResult.Failed(
                            CompactFailureReason.TURN_TOO_LARGE,
                        )
                    }
                    CompactPackResult.Failed -> {
                        noteFullCompact("failed")
                        return@withConversationLock CompactResult.Failed(CompactFailureReason.GENERIC)
                    }
                }
            }
            val stored = if (chapters.isEmpty()) {
                null
            } else {
                val encoded = encodeResidencyChapters(chapters)
                if (encoded.isBlank()) {
                    return@withConversationLock CompactResult.Failed(CompactFailureReason.GENERIC)
                }
                encoded
            }
            val covered = if (stored == null) emptyList() else compacted
            val tailTokens = measureOriginalTokens(turns.drop(prefix.size), config, includeImages)
            val keptOriginalTokens = if (stored == null) {
                applyCompletedTurnTokens(
                    compactIndexTokens(prefix.indices.toList(), tokensByTurn),
                    tailTokens,
                )
            } else {
                tailTokens
            }
            val seated = commitFinishedCompact(
                conversations = conversations,
                conversationId = conversationId,
                turns = turns,
                clearExistingBatches = true,
                coveredTurnIds = covered.map { turns[it].turnId },
                batchId = COVERED_BATCH_ID,
                digestTurnId = covered.lastOrNull()?.let { turns[it].turnId },
                digest = stored,
                digestRev = 1,
                formatRev = if (stampFormatRev) CompactFormat.CURRENT_REV else null,
                summaryTokens = if (stored == null) 0 else compactChapterTokens(chapters),
                keptOriginalTokens = keptOriginalTokens,
            )
            retireLegacyCapsules(conversationId, path)
            noteFullCompact(
                "wrote chapters=${chapters.size} messages=$compressedMessages/$totalMessages tokens=${seated.tokens}",
            )
            if (appContext != null) {
                noteFullCompact("persist.tail.start")
                persistOriginalBand(turns, prefix.size, batched, appContext)
                noteFullCompact("persist.tail.done")
            }
            return@withConversationLock CompactResult.Created("summary_formal_${UUID.randomUUID()}")
        }
        val uncovered = prefix.filter { !it.batched }
        val oldDigest = loadPathDigest(path, loaded.graphById)
        val oldChapters = oldDigest?.chapters.orEmpty()
        val members = when {
            oldDigest == null -> prefix
            uncovered.isEmpty() -> {
                return@withConversationLock CompactResult.NotNeeded
            }
            else -> uncovered
        }
        onProgress?.invoke(CompactBatchProgress(completed = 0, total = 1))
        val packed = foldUncoveredSlice(
            members = members,
            oldChapters = oldChapters,
            config = config,
        )
        val chapters = when (packed) {
            is CompactPackResult.Chapters -> packed.chapters
            CompactPackResult.TurnTooLarge ->
                return@withConversationLock CompactResult.Failed(CompactFailureReason.TURN_TOO_LARGE)
            CompactPackResult.Failed ->
                return@withConversationLock CompactResult.Failed(CompactFailureReason.GENERIC)
        }
        val stored = encodeResidencyChapters(chapters)
        if (stored.isBlank()) {
            return@withConversationLock CompactResult.Failed(CompactFailureReason.GENERIC)
        }
        commitFinishedCompact(
            conversations = conversations,
            conversationId = conversationId,
            turns = turns,
            clearExistingBatches = false,
            coveredTurnIds = prefix.map { it.turnId },
            batchId = COVERED_BATCH_ID,
            digestTurnId = prefix.last().turnId,
            digest = stored,
            digestRev = (oldDigest?.rev ?: 0) + 1,
            formatRev = if (stampFormatRev) CompactFormat.CURRENT_REV else null,
            summaryTokens = compactChapterTokens(chapters),
            keptOriginalTokens = measureOriginalTokens(turns.drop(prefix.size), config, includeImages),
        )
        onProgress?.invoke(CompactBatchProgress(completed = 1, total = 1))
        CompactResult.Created("summary_formal_${UUID.randomUUID()}")
        } finally {
            if (fullRecompact) optimizeLogFile = null
        }
    }

    private suspend fun foldUncoveredSlice(
        members: List<CompactTurn>,
        oldChapters: List<String>,
        config: AutomaticCompactConfig,
    ): CompactPackResult {
        val added = packTurnsIntoChapters(turns = members, config = config)
        return when (added) {
            is CompactPackResult.Chapters -> CompactPackResult.Chapters(oldChapters + added.chapters)
            else -> added
        }
    }

    private suspend fun packTurnsIntoChapters(
        turns: List<CompactTurn>,
        config: AutomaticCompactConfig,
    ): CompactPackResult {
        if (turns.isEmpty()) return CompactPackResult.Failed
        val remaining = turns.toMutableList()
        val chapters = mutableListOf<String>()
        while (remaining.isNotEmpty()) {
            var candidate = remaining.toList()
            while (true) {
                when (val outcome = compactTurnSlice(candidate, config)) {
                    is CompactLlmOutcome.Complete -> {
                        chapters += outcome.text
                        repeat(candidate.size) { remaining.removeAt(0) }
                    }
                    CompactLlmOutcome.Truncated -> {
                        if (candidate.size == 1) return CompactPackResult.TurnTooLarge
                        candidate = candidate.take((candidate.size / 2).coerceAtLeast(1))
                        continue
                    }
                    CompactLlmOutcome.Failed -> return CompactPackResult.Failed
                }
                break
            }
        }
        return CompactPackResult.Chapters(chapters)
    }

    private suspend fun compactTurnSlice(
        turns: List<CompactTurn>,
        config: AutomaticCompactConfig,
    ): CompactLlmOutcome {
        if (turns.isEmpty()) return CompactLlmOutcome.Failed
        val members = if (compactTurnsNeedBodyHydration(turns)) hydrateTurns(turns) else turns
        val userBody = buildSummaryUserPrompt(members)
        return runCompactLlm(
            config = config,
            systemPrompt = BuiltInPrompts.CONTEXT_COMPACT_SYSTEM,
            userBody = userBody,
            logLabel = "Context summary",
        )
    }

    private suspend fun runCompactLlm(
        config: AutomaticCompactConfig,
        systemPrompt: String,
        userBody: String,
        logLabel: String,
    ): CompactLlmOutcome {
        val prompt = listOf(
            ChatMessage(
                text = userBody,
                participant = Participant.USER,
                status = MessageStatus.SUCCESS,
            ),
        )
        val provider = config.provider ?: return CompactLlmOutcome.Failed
        val modelId = ModelId.parse(providers.canonicalModelId(config.request.model)).modelName
        val providerConfig = ProviderConfig(
            apiKey = config.apiKey,
            modelId = modelId,
            systemPrompt = systemPrompt,
            maxContextWindow = config.generationConfig.maxContextWindow.coerceAtLeast(4096),
            thinkingEnabled = false,
            baseUrl = config.baseUrl,
            maxTokens = compactMaxOutputTokens(config.freezeThreshold),
        )
        var text = ""
        var error: GenerationError? = null
        suspend fun collect() {
            provider.generateResponse(prompt, providerConfig).collect { event ->
                when (event) {
                    is StreamEvent.TextChunk -> text += event.text
                    is StreamEvent.Error -> error = event.error
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
            DebugLog.e("BatchCompactEngine", "$logLabel failed", e)
            return CompactLlmOutcome.Failed
        }
        val outcomeError = error
        return when {
            outcomeError != null && compactLlmShouldShrinkSlice(outcomeError) -> {
                DebugLog.e(
                    "BatchCompactEngine",
                    "$logLabel truncated: ${outcomeError.userMessage()}",
                )
                CompactLlmOutcome.Truncated
            }
            outcomeError == null -> {
                val body = text.trim()
                if (body.isBlank()) CompactLlmOutcome.Failed else CompactLlmOutcome.Complete(body)
            }
            else -> {
                DebugLog.e("BatchCompactEngine", "$logLabel error: ${outcomeError.userMessage()}")
                CompactLlmOutcome.Failed
            }
        }
    }

    private suspend fun ensureTurnIds(conversationId: String, trace: Boolean = false) {
        val loaded = loadProviderPath(conversationId, trace = trace, traceLabel = "ensureTurnIds") ?: return
        val turns = CompactTurnIndexer.finishedDialogueTurns(loaded.path)
        if (trace) noteFullCompact("ensureTurnIds.assign turns=${turns.size}")
        for (turn in turns) {
            val id = if (turn.turnId.startsWith("pending_")) {
                "turn_${UUID.randomUUID()}"
            } else {
                turn.turnId
            }
            conversations.assignTurnIdIfAbsent(turn.anchorId, id)
        }
        if (trace) noteFullCompact("ensureTurnIds.done")
    }

    private suspend fun retireLegacyCapsules(conversationId: String, path: List<ChatMessage>) {
        path.filter { it.isContextCompact() }.forEach { capsule ->
            runCatching { conversations.removeContextCompact(capsule.id) }
        }
    }

    private suspend fun loadProviderPath(
        conversationId: String,
        trace: Boolean = false,
        traceLabel: String = "loadPath",
    ): LoadedCompactPath? {
        if (trace) noteFullCompact("$traceLabel.graph.start")
        val graph = conversations.getCompactMessageGraph(conversationId)
        if (trace) noteFullCompact("$traceLabel.graph.rows=${graph.size}")
        val selectedChildren = conversations.restoreBranchSelections(conversationId)
        if (trace) noteFullCompact("$traceLabel.assemble.start")
        val path = assembleCompactProviderPath(graph, selectedChildren)
        if (trace) noteFullCompact("$traceLabel.assemble.messages=${path.size}")
        return LoadedCompactPath(
            path = path,
            graphById = graph.associateBy { it.id },
        )
    }

    private suspend fun loadPathDigest(
        path: List<ChatMessage>,
        graphById: Map<String, CompactMessageGraphRow>,
    ): PathDigest? = loadNewestPathDigest(path, graphById, conversations::getCompactDigest)

    private suspend fun hydrateTurns(turns: List<CompactTurn>): List<CompactTurn> =
        hydrateCompactTurns(
            turns = turns,
            loadEntities = conversations::getMessagesByIds,
            includeStoredTranscriptions = false,
        )

    private suspend fun measureOriginalTokens(
        turns: List<CompactTurn>,
        config: AutomaticCompactConfig,
        includeImages: Boolean,
    ): Int {
        var sum = 0
        for (turn in turns) {
            sum = applyCompletedTurnTokens(
                sum,
                estimateHydratedTurnTokens(
                    turn = turn,
                    includeImages = includeImages,
                    userPrepend = config.userPrepend,
                    userPostpend = config.userPostpend,
                    askMode = config.generationContext.askMode,
                ),
            )
        }
        return sum
    }

    private suspend fun estimateHydratedTurnTokens(
        turn: CompactTurn,
        includeImages: Boolean,
        userPrepend: String?,
        userPostpend: String?,
        askMode: Boolean,
    ): Int {
        val hydrated = hydrateOneTurn(turn)
        return contextWindowUsage(
            projectGenerationInputMessages(
                messages = hydrated.messages,
                includeImages = includeImages,
                userPrepend = userPrepend,
                userPostpend = userPostpend,
                askMode = askMode,
            ),
            tokenBudget = Int.MAX_VALUE,
            fixedTokenCost = 0,
        ).estimatedTokenCount
    }

    /**
     * Turns that stayed original: the retain tail, plus any prefix still under the threshold.
     * Each page is archived and dropped before the next page is read.
     */
    private suspend fun persistOriginalBand(
        turns: List<CompactTurn>,
        prefixSize: Int,
        batched: BooleanArray,
        appContext: Context,
    ) {
        for (index in turns.indices) {
            val covered = index < prefixSize && index < batched.size && batched[index]
            if (!covered) persistTurnTools(turns[index], appContext)
        }
    }

    private suspend fun persistTurnTools(turn: CompactTurn, appContext: Context) {
        val ids = turn.messages.map { it.id }
        for (chunk in ids.chunked(FULL_RECOMPACT_HYDRATE_CHUNK)) {
            DetailArchive.persistEligibleTools(
                projectHydratedEntities(
                    conversations.getMessagesByIds(chunk),
                    includeStoredTranscriptions = false,
                ),
                detailStore,
                appContext,
            )
        }
    }

    /**
     * One turn, in id pages. Each page is projected and dropped before the next page is read.
     * The caller keeps the turn only while measuring it or appending it to the piece being sent.
     */
    private suspend fun hydrateOneTurn(turn: CompactTurn): CompactTurn {
        if (!compactTurnsNeedBodyHydration(listOf(turn))) return turn
        val projected = hydrateMessagesByIds(
            ids = turn.messages.map { it.id },
            loadEntities = conversations::getMessagesByIds,
            includeStoredTranscriptions = false,
            chunkSize = FULL_RECOMPACT_HYDRATE_CHUNK,
        )
        return replaceHydratedTurns(listOf(turn), projected).first()
    }

    private suspend fun foldMeasuredSlice(
        turns: List<CompactTurn>,
        indices: List<Int>,
        tokensByTurn: IntArray,
        oldChapters: List<String>,
        config: AutomaticCompactConfig,
    ): CompactPackResult {
        if (indices.isEmpty()) return CompactPackResult.Chapters(oldChapters)
        val chapters = oldChapters.toMutableList()
        val remaining = indices.toMutableList()
        while (remaining.isNotEmpty()) {
            var candidate = remaining.toList()
            while (true) {
                noteFullCompact(
                    "prompt.load turns=${candidate.size} tokens=${compactIndexTokens(candidate, tokensByTurn)}",
                )
                val userBody = promptForTurnIndices(turns, candidate)
                noteFullCompact("prompt.ready chars=${userBody.length}")
                when (
                    val outcome = runCompactLlm(
                        config = config,
                        systemPrompt = BuiltInPrompts.CONTEXT_COMPACT_SYSTEM,
                        userBody = userBody,
                        logLabel = "Context summary",
                    )
                ) {
                    is CompactLlmOutcome.Complete -> {
                        chapters += outcome.text
                        repeat(candidate.size) { remaining.removeAt(0) }
                    }
                    CompactLlmOutcome.Truncated -> {
                        if (candidate.size == 1) return CompactPackResult.TurnTooLarge
                        candidate = candidate.take(candidate.size / 2)
                        continue
                    }
                    CompactLlmOutcome.Failed -> return CompactPackResult.Failed
                }
                break
            }
        }
        return CompactPackResult.Chapters(chapters)
    }

    private suspend fun promptForTurnIndices(
        turns: List<CompactTurn>,
        indices: List<Int>,
    ): String = buildString {
        for (index in indices) {
            append(formatBatchOriginals(listOf(hydrateOneTurn(turns[index]))))
        }
    }

    private fun openOptimizeLog(context: Context?) {
        optimizeLogFile = null
        val ctx = context ?: return
        runCatching {
            val file = OptimizeDiagnosticLog.file(ctx)
            file.parentFile?.mkdirs()
            FileOutputStream(file, false).use { out ->
                out.write(ByteArray(0))
                out.fd.sync()
            }
            optimizeLogFile = file
        }
    }

    private fun noteFullCompact(message: String) {
        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val maxMb = runtime.maxMemory() / (1024 * 1024)
        val line = "compact.full $message heap=${usedMb}M/${maxMb}M"
        CrashReporter.note(line)
        DebugLog.d("BatchCompactEngine", line)
        val file = optimizeLogFile ?: return
        runCatching {
            FileOutputStream(file, true).use { out ->
                out.write((line + "\n").toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
        }
    }

    private suspend fun <T> withConversationLock(
        conversationId: String,
        block: suspend () -> T,
    ): T {
        val mutex = conversationLocks.getOrPut(conversationId) { Mutex() }
        return mutex.withLock { block() }
    }

    companion object {
        const val COVERED_BATCH_ID = "summary"
        private const val FULL_RECOMPACT_HYDRATE_CHUNK = 4

        fun pathHasCompactArtifacts(messages: List<ChatMessage>): Boolean {
            if (resolvePathDigest(messages) != null) return true
            if (messages.any { it.isSuccessfulContextCompact() }) return true
            return CompactTurnIndexer.finishedDialogueTurns(messages).any { it.batched }
        }

        fun buildSummaryUserPrompt(members: List<CompactTurn>): String =
            formatBatchOriginals(members)

        fun formatBatchOriginals(members: List<CompactTurn>): String = buildString {
            members.forEach { turn ->
                append("--- turn ")
                append(turn.turnId)
                append(" ---\n")
                turn.messages.forEach { message ->
                    append('\n')
                    when {
                        message.id.startsWith(Constants.TOOL_MSG_PREFIX) -> {
                            append("[Assistant tool request]\n")
                            append(message.text)
                        }
                        message.id.startsWith(Constants.RESULT_MSG_PREFIX) -> {
                            append("[Tool result]\n")
                            append(message.text)
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
                    append('\n')
                }
                append('\n')
            }
        }
    }
}

private data class LoadedCompactPath(
    val path: List<ChatMessage>,
    val graphById: Map<String, CompactMessageGraphRow>,
)

private sealed class CompactPackResult {
    data class Chapters(val chapters: List<String>) : CompactPackResult()
    data object TurnTooLarge : CompactPackResult()
    data object Failed : CompactPackResult()
}

private sealed class CompactLlmOutcome {
    data class Complete(val text: String) : CompactLlmOutcome()
    data object Truncated : CompactLlmOutcome()
    data object Failed : CompactLlmOutcome()
}

/** Originals that would actually be folded: unfinished prefix outside the retain window. */
internal fun uncompressedCompactThresholdMessages(
    path: List<ChatMessage>,
    retainTurns: Int,
): List<ChatMessage> {
    val turns = CompactTurnIndexer.finishedDialogueTurns(path)
    if (turns.size <= retainTurns) return emptyList()
    return turns.dropLast(retainTurns).filter { !it.batched }.flatMap { it.messages }
}

internal fun automaticBatchCompactNeeded(
    path: List<ChatMessage>,
    contextLimit: Int,
    retainTurns: Int,
    fixedTokenCost: Int = 0,
    userPrepend: String? = null,
    userPostpend: String? = null,
    includeImages: Boolean = true,
): Boolean {
    if (path.isEmpty() || retainTurns < 0) return false
    val turns = CompactTurnIndexer.finishedDialogueTurns(path)
    if (turns.size <= retainTurns) return false
    val prefix = turns.dropLast(retainTurns)
    if (prefix.none { !it.batched }) return false
    return contextWindowUsage(
        projectGenerationInputMessages(
            messages = uncompressedCompactThresholdMessages(path, retainTurns),
            includeImages = includeImages,
            userPrepend = userPrepend,
            userPostpend = userPostpend,
        ),
        contextLimit.coerceAtLeast(1),
        fixedTokenCost = fixedTokenCost,
    ).estimatedTokenCount >= contextLimit.coerceAtLeast(1)
}

/**
 * Replay send-by-send automatic compact: each time uncovered prefix originals (retain
 * window excluded) would have crossed [compactThreshold], take that prefix as one slice.
 * Turns still under the threshold at the end are omitted (stay original).
 */
internal fun replayAutomaticCompactSlices(
    turns: List<CompactTurn>,
    retainTurns: Int,
    compactThreshold: Int,
    fixedTokenCost: Int = 0,
    userPrepend: String? = null,
    userPostpend: String? = null,
    includeImages: Boolean = true,
): List<List<CompactTurn>> {
    val retain = retainTurns.coerceAtLeast(0)
    if (turns.size <= retain) return emptyList()
    val batchedIds = mutableSetOf<String>()
    val slices = mutableListOf<List<CompactTurn>>()
    for (end in (retain + 1)..turns.size) {
        val soFar = turns.take(end)
        val path = soFar.flatMap { turn ->
            turn.messages.map { message ->
                if (turn.turnId in batchedIds) {
                    message.copy(
                        compactBatchId = message.compactBatchId?.takeIf { it.isNotBlank() }
                            ?: BatchCompactEngine.COVERED_BATCH_ID,
                    )
                } else {
                    message
                }
            }
        }
        if (
            !automaticBatchCompactNeeded(
                path = path,
                contextLimit = compactThreshold,
                retainTurns = retain,
                fixedTokenCost = fixedTokenCost,
                userPrepend = userPrepend,
                userPostpend = userPostpend,
                includeImages = includeImages,
            )
        ) {
            continue
        }
        val uncovered = soFar.dropLast(retain).filter { it.turnId !in batchedIds }
        if (uncovered.isEmpty()) continue
        slices += uncovered
        batchedIds += soFar.dropLast(retain).map { it.turnId }
    }
    return slices
}

/**
 * Same cadence as [replayAutomaticCompactSlices], using per-turn token estimates so a full
 * recompact can close a slice as soon as that prefix crosses the threshold.
 */
internal fun replayAutomaticCompactSliceIndices(
    turnCount: Int,
    retainTurns: Int,
    compactThreshold: Int,
    tokensByTurn: IntArray,
    fixedTokenCost: Int = 0,
): List<List<Int>> {
    val retain = retainTurns.coerceAtLeast(0)
    if (turnCount <= retain || tokensByTurn.size < turnCount) return emptyList()
    val batched = BooleanArray(turnCount)
    val slices = mutableListOf<List<Int>>()
    for (end in (retain + 1)..turnCount) {
        val prefixEnd = end - retain
        val uncovered = sliceClosedAtPrefix(
            prefixEnd = prefixEnd,
            batched = batched,
            tokensByTurn = tokensByTurn,
            compactThreshold = compactThreshold,
            fixedTokenCost = fixedTokenCost,
        ) ?: continue
        slices += uncovered
        for (index in 0 until prefixEnd) batched[index] = true
    }
    return slices
}

/** Uncovered turns that have slid out of the retain window and just crossed the compact threshold. */
internal fun sliceClosedAtPrefix(
    prefixEnd: Int,
    batched: BooleanArray,
    tokensByTurn: IntArray,
    compactThreshold: Int,
    fixedTokenCost: Int = 0,
): List<Int>? {
    if (prefixEnd <= 0) return null
    val uncovered = (0 until prefixEnd).filter { index ->
        index < batched.size && !batched[index]
    }
    if (uncovered.isEmpty()) return null
    val estimated = (
        uncovered.fold(0L) { total, index ->
            total + tokensByTurn.getOrElse(index) { 0 }.toLong()
        } + fixedTokenCost.coerceAtLeast(0).toLong()
    ).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    if (estimated < compactThreshold.coerceAtLeast(1)) return null
    return uncovered
}

private fun compactIndexTokens(indices: List<Int>, tokensByTurn: IntArray): Int =
    indices.fold(0L) { total, index ->
        total + tokensByTurn.getOrElse(index) { 0 }.toLong()
    }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
