package com.newoether.agora.viewmodel

import android.content.Context
import com.newoether.agora.automation.ConversationExecutionCoordinator
import com.newoether.agora.data.local.UiMessageGraphRow
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Combines the open conversation's Room graph and runtime overlay into stable UI projections.
 *
 * [ConversationRenderStore] remains the atomic projection store. This assembler owns only the
 * open-conversation collectors and mirror values; it cannot submit runtime commands or mutate a
 * Run. Its single collection job is owned by the injected ViewModel scope.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class ConversationUiStateAssembler(
    private val conversations: ConversationRepository,
    private val registry: ConversationStateRegistry,
    private val executionCoordinator: ConversationExecutionCoordinator,
    private val currentConversationId: StateFlow<String?>,
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val projectionDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val onConversationLoadFailed: (String) -> Unit = {},
) {
    val renderStore = ConversationRenderStore()

    val allMessages: StateFlow<List<ChatMessage>> = renderStore.snapshot
        .map { it.allMessages }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val workingSet = ConversationWorkingSet(conversations) { entity ->
        entity.toUiChatMessage(appContext)
    }

    val sendTokenCache: StateFlow<SendTokenCache> = currentConversationId
        .flatMapLatest { id ->
            if (id == null) flowOf(SendTokenCache()) else conversations.observeSendTokenCache(id)
        }
        .stateIn(scope, SharingStarted.Eagerly, SendTokenCache())

    suspend fun persistSendTokenCache(conversationId: String, cache: SendTokenCache) {
        conversations.setSendTokenCache(conversationId, cache)
    }

    val messages: StateFlow<List<ChatMessage>> = renderStore.snapshot
        .mapLatest { snapshot ->
            withContext(projectionDispatcher) {
                ConversationUiState.resolvePath(
                    snapshot.allMessages,
                    snapshot.streamingMessage,
                    snapshot.selectedChildren,
                )
            }
        }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _totalTokens = MutableStateFlow(0)
    val totalTokens: StateFlow<Int> = _totalTokens.asStateFlow()

    private val _isLastPage = MutableStateFlow(true)
    val isLastPage: StateFlow<Boolean> = _isLastPage.asStateFlow()

    private val _hasEarlierPage = MutableStateFlow(false)
    val hasEarlierPage: StateFlow<Boolean> = _hasEarlierPage.asStateFlow()

    private val _earlierPageGeneration = MutableStateFlow(0)
    val earlierPageGeneration: StateFlow<Int> = _earlierPageGeneration.asStateFlow()

    private val _ledgerCursor = MutableStateFlow("seated|0")
    val ledgerCursor: StateFlow<String> = _ledgerCursor.asStateFlow()

    private val _loadedMessagesConversationId = MutableStateFlow<String?>(null)
    val loadedMessagesConversationId: StateFlow<String?> =
        _loadedMessagesConversationId.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _generatingInConversationId = MutableStateFlow<String?>(null)
    val generatingInConversationId: StateFlow<String?> =
        _generatingInConversationId.asStateFlow()

    private val _generationSnapshot = MutableStateFlow(ConversationGenerationSnapshot())
    val generationSnapshot: StateFlow<ConversationGenerationSnapshot> =
        _generationSnapshot.asStateFlow()

    private val generationMirror = ConversationGenerationMirror(
        currentConversationId = currentConversationId,
        onSnapshot = { conversationId, snapshot ->
            _generationSnapshot.value = snapshot
            renderStore.setStreamingMessage(snapshot.streamingMessage)
            _isLoading.value = snapshot.isLoading
            _generatingInConversationId.value =
                if (snapshot.isGenerating) conversationId else null
        },
    )

    private var collectionJob: Job? = null
    private val pageGate = Mutex()
    private var pageRows: List<UiMessageGraphRow> = emptyList()
    private var openConversationId: String? = null
    private var restoredChildren: Map<String?, String> = emptyMap()
    private var selectionInstalled = false
    private var appliedSelection: Map<String?, String> = emptyMap()
    private var pinnedToLastPage = true
    private var pageEndTurnExclusive: Int? = null
    private var pageStartTurnExclusive = 0
    private var projectedById: Map<String, ProjectedUiMessage> = emptyMap()
    private var tokenCounts: Map<String, Int>? = null
    private var memoryTokenTotal = 0
    private var persistedTokenTotal: Int? = null
    private var lastLedgerIdentity = 0
    private var lastLoggedPageKey = ""
    private var openState: ConversationGenerationState? = null

    fun start() {
        if (collectionJob != null) return
        collectionJob = scope.launch {
            currentConversationId.collectLatest { id ->
                _loadedMessagesConversationId.value = null
                if (id != null) {
                    try {
                        collectConversation(id)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        DebugLog.e("ConversationUi", "Failed to load conversation projection", error)
                        if (currentConversationId.value == id) {
                            // The store deliberately retains the previous conversation under the
                            // switching cover until the target's first atomic snapshot arrives.
                            // A real load failure must retire that protected snapshot before the
                            // owner releases the cover, or the old graph/loading mirror would be
                            // exposed under the newly selected conversation.
                            clearAllProjection()
                            onConversationLoadFailed(id)
                        }
                    }
                } else {
                    clearAllProjection()
                }
            }
        }
    }

    fun markActive(conversationId: String) {
        if (currentConversationId.value == conversationId) {
            _isLoading.value = true
            _generatingInConversationId.value = conversationId
        }
    }

    fun markIdle(conversationId: String) {
        if (currentConversationId.value == conversationId) {
            _isLoading.value = false
            _generatingInConversationId.value = null
        }
    }

    fun commitTerminalStreamingMessage(conversationId: String, message: ChatMessage) {
        if (currentConversationId.value == conversationId) {
            renderStore.commitTerminalStreamingMessage(message)
        }
    }

    fun clearConversationGraph() {
        renderStore.clear()
        _loadedMessagesConversationId.value = null
        resetTranscriptPage()
    }

    fun requestEarlierPage() {
        scope.launch { showEarlierPage() }
    }

    suspend fun showLastPage() {
        pageGate.withLock { showLastPageLocked() }
    }

    suspend fun showEarlierPage() {
        pageGate.withLock {
            if (!_hasEarlierPage.value || openConversationId == null) return@withLock
            pinnedToLastPage = false
            pageEndTurnExclusive = pageStartTurnExclusive
            publishPageLocked()
            _earlierPageGeneration.value += 1
        }
    }

    private fun clearAllProjection() {
        clearConversationGraph()
        _generationSnapshot.value = ConversationGenerationSnapshot()
        _isLoading.value = false
        _generatingInConversationId.value = null
    }

    private suspend fun collectConversation(id: String) = coroutineScope {
        workingSet.clear()
        conversations.ensureRunRecovery()
        val state = registry.getOrCreate(id)
        val automationRunning =
            id in executionCoordinator.activeAutomationConversationIds.value
        if (!state.generating.value && !automationRunning) {
            conversations.fixStuckMessages(id)
        }

        val conversation = conversations.getConversation(id)
        val restored = withContext(projectionDispatcher) {
            conversation?.selectedBranchesJson?.let { raw ->
                runCatching {
                    Json.decodeFromString<Map<String, String>>(raw)
                        .mapKeys { (key, _) -> if (key == "null") null else key }
                }.getOrNull()
            }.orEmpty()
        }
        pageGate.withLock {
            openConversationId = id
            restoredChildren = restored
            selectionInstalled = false
            appliedSelection = emptyMap()
            pinnedToLastPage = true
            pageEndTurnExclusive = null
            pageStartTurnExclusive = 0
            pageRows = emptyList()
            projectedById = emptyMap()
            tokenCounts = null
            persistedTokenTotal = conversation?.transcriptTokenTotal
            memoryTokenTotal = persistedTokenTotal ?: 0
            _totalTokens.value = memoryTokenTotal
            lastLedgerIdentity = 0
            lastLoggedPageKey = ""
            _isLastPage.value = true
            _hasEarlierPage.value = false
            _earlierPageGeneration.value = 0
            openState = state
        }
        var generationMirrorStarted = false
        launch {
            renderStore.snapshot
                .map { it.selectedChildren }
                .distinctUntilChanged()
                .collect { selected ->
                    pageGate.withLock {
                        if (openConversationId != id || !selectionInstalled) return@withLock
                        if (selected == appliedSelection || pageRows.isEmpty()) return@withLock
                        publishPageLocked()
                    }
                }
        }
        conversations.observeUiMessageGraph(id).collect { rows ->
            pageGate.withLock {
                if (openConversationId != id) return@withLock
                pageRows = rows
                publishPageLocked()
                _loadedMessagesConversationId.value = id
                if (!generationMirrorStarted) {
                    generationMirrorStarted = true
                    generationMirror.publishCurrent(id, state)
                    launch { generationMirror.collect(id, state) }
                }
            }
        }
    }

    private fun resetTranscriptPage() {
        openConversationId = null
        pageRows = emptyList()
        projectedById = emptyMap()
        tokenCounts = null
        memoryTokenTotal = 0
        persistedTokenTotal = null
        lastLedgerIdentity = 0
        lastLoggedPageKey = ""
        _totalTokens.value = 0
        _isLastPage.value = true
        _hasEarlierPage.value = false
        _earlierPageGeneration.value = 0
        openState = null
    }

    private suspend fun showLastPageLocked() {
        if (openConversationId == null) return
        if (pinnedToLastPage && _isLastPage.value) return
        pinnedToLastPage = true
        pageEndTurnExclusive = null
        publishPageLocked()
    }

    private suspend fun publishPageLocked() {
        val id = openConversationId ?: return
        val selected = if (selectionInstalled) {
            renderStore.selectedChildren
        } else {
            restoredChildren
        }
        val chain = selectedTranscriptChain(pageRows, selected)
        val requestedEnd = if (pinnedToLastPage) null else pageEndTurnExclusive
        val page = transcriptPage(chain = chain, pageEndTurnExclusive = requestedEnd)
        val spanCount = transcriptTurnSpans(chain).size
        val appliedEnd = (requestedEnd ?: spanCount).coerceIn(0, spanCount)
        pageEndTurnExclusive = if (appliedEnd == spanCount) null else appliedEnd
        if (page.isLastPage) pinnedToLastPage = true
        pageStartTurnExclusive = page.startTurnExclusive
        _isLastPage.value = page.isLastPage
        _hasEarlierPage.value = page.hasEarlier
        seatTokenTotal(id, pageRows)
        seatLedgerCursor(chain)
        val streamingMessageId = openState?.streamingMessage?.value?.id
        val cached = projectedById.mapValues { it.value.message }
        val toFetch = incrementalUiMessageIdsToFetch(
            rows = page.chain,
            previous = projectedById,
            existingById = cached,
            streamingMessageId = streamingMessageId,
        )
        val decoded = if (toFetch.isEmpty()) {
            emptyMap()
        } else {
            conversations.getUiMessagesByIds(id, toFetch).associate { entity ->
                entity.id to entity.toUiChatMessage(appContext).withThoughtPlaceholderIfNeeded()
            }
        }
        val (mapped, nextMap) = assembleIncrementalUiMessages(
            rows = page.chain,
            previous = projectedById,
            existingById = cached,
            decoded = decoded,
            streamingMessageId = streamingMessageId,
        )
        projectedById = nextMap
        val byId = mapped.associateBy { it.id }
        val stubs = siblingTranscriptStubs(pageRows, page.chain).associateBy { it.id }
        val included = page.chain.mapTo(HashSet()) { it.id }.apply { addAll(stubs.keys) }
        val pageMessages = pageRows.mapNotNull { row ->
            if (row.id !in included) return@mapNotNull null
            byId[row.id] ?: stubs[row.id] ?: row.toTranscriptStub()
        }
        val reused = reuseProjectedMessageList(renderStore.allMessages, pageMessages)
        if (!selectionInstalled) {
            renderStore.replaceConversation(allMessages = reused, selectedChildren = selected)
            selectionInstalled = true
        } else if (reused !== renderStore.allMessages) {
            renderStore.setAllMessages(reused)
        }
        appliedSelection = selected
        val pageKey = page.chain.joinToString(",") { it.id }
        if (pageKey != lastLoggedPageKey) {
            lastLoggedPageKey = pageKey
            val turns = transcriptTurnSpans(page.chain).size
            ConversationLoadLog.note(
                appContext,
                "turns=$turns msgs=${page.chain.size} last=${page.isLastPage} earlier=${page.hasEarlier}",
            )
        }
    }

    private suspend fun seatTokenTotal(id: String, rows: List<UiMessageGraphRow>) {
        val next = countedTranscriptTokens(rows)
        val seated = if (tokenCounts == null) persistedTokenTotal else memoryTokenTotal
        val (total, applied) = advanceTranscriptTokenTotal(seated, tokenCounts, next)
        tokenCounts = applied
        memoryTokenTotal = total
        _totalTokens.value = total
        val seatEmpty = persistedTokenTotal == null && next.isEmpty()
        if (!seatEmpty && total != persistedTokenTotal) {
            conversations.setTranscriptTokenTotal(id, total)
            persistedTokenTotal = total
        }
    }

    private fun seatLedgerCursor(chain: List<UiMessageGraphRow>) {
        val identity = transcriptLedgerIdentity(chain)
        if (identity == lastLedgerIdentity && lastLedgerIdentity != 0) return
        lastLedgerIdentity = identity
        _ledgerCursor.value = transcriptLedgerCursor(chain)
    }
}

/** Identity-only fingerprint so the context bar can refresh without seeing message bodies. */
internal fun contextGraphRevision(messages: List<ChatMessage>): Long {
    var hash = messages.size.toLong()
    for (message in messages) {
        hash = hash * 31 + message.id.hashCode()
        hash = hash * 31 + message.status.ordinal
        hash = hash * 31 + message.compactDigestRev
        message.compactBatchId?.let { hash = hash * 31 + it.hashCode() }
    }
    return hash
}
