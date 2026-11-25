package com.newoether.agora.viewmodel

import com.newoether.agora.api.util.PathDigest
import com.newoether.agora.api.util.applyTimeBandProjection
import com.newoether.agora.api.util.isContextProjectionMessageId
import com.newoether.agora.api.util.projectGenerationStatusesForApi
import com.newoether.agora.data.formatResidencyChaptersForWire
import com.newoether.agora.data.local.CompactMessageGraphRow
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.parseResidencyChapters
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.tool.CompactTurn
import com.newoether.agora.tool.CompactTurnIndexer
import com.newoether.agora.util.Constants

internal fun CompactMessageGraphRow.toStubMessageEntity(): MessageEntity = MessageEntity(
    id = id,
    conversationId = conversationId,
    parentId = parentId,
    text = "",
    participant = participant,
    status = status,
    timestamp = timestamp,
    runId = runId,
    runSequence = runSequence,
    modelName = modelName,
    consumedAtPass = consumedAtPass,
    turnId = turnId,
    compactLocked = compactLocked,
    compactBatchId = compactBatchId,
    compactDigestRev = compactDigestRev,
)

internal fun CompactMessageGraphRow.toPathChatMessage(): ChatMessage = ChatMessage(
    id = id,
    parentId = parentId,
    text = "",
    status = status,
    participant = participant,
    timestamp = timestamp,
    modelName = modelName,
    runId = runId,
    runSequence = runSequence,
    consumedAtPass = consumedAtPass,
    turnId = turnId,
    compactLocked = compactLocked,
    compactBatchId = compactBatchId,
    compactDigestRev = compactDigestRev,
)

internal fun selectedVisiblePath(
    graph: List<CompactMessageGraphRow>,
    selectedChildren: Map<String?, String>,
): List<ChatMessage> {
    if (graph.isEmpty()) return emptyList()
    return ConversationUiState.resolvePath(
        allMessages = graph.map { it.toPathChatMessage() },
        streamingMsg = null,
        selectedChildren = selectedChildren,
    )
}

internal fun assembleCompactProviderPath(
    graph: List<CompactMessageGraphRow>,
    selectedChildren: Map<String?, String>,
): List<ChatMessage> {
    if (graph.isEmpty()) return emptyList()
    val entities = graph.map { it.toStubMessageEntity() }
    val selectedPath = selectedVisiblePath(graph, selectedChildren)
    val byId = entities.associateBy { it.id }
    val selectedEntities = selectedPath.mapNotNull { byId[it.id] }
    val expanded = ApiPathAssembler.assemble(selectedEntities, entities)
    return projectGenerationStatusesForApi(
        projectProviderMessages(expanded, includeStoredTranscriptions = false),
    )
}

internal fun generationAncestorIds(
    graphById: Map<String, CompactMessageGraphRow>,
    startId: String?,
    stopAtSuccessfulCompact: Boolean,
): List<String> {
    val ids = ArrayList<String>()
    val seen = HashSet<String>()
    var currentId = startId
    while (currentId != null && seen.add(currentId)) {
        val row = graphById[currentId] ?: break
        ids += row.id
        if (
            stopAtSuccessfulCompact &&
            row.id.startsWith(Constants.COMPACT_MSG_PREFIX) &&
            row.status == MessageStatus.SUCCESS
        ) {
            break
        }
        currentId = row.parentId
    }
    ids.reverse()
    return ids
}

internal fun generationProtocolIds(
    graph: List<CompactMessageGraphRow>,
    ancestorIds: List<String>,
): List<String> {
    val byId = graph.associateBy { it.id }
    val children = graph.groupBy { it.parentId }
    val order = compareBy<CompactMessageGraphRow>(
        { it.runSequence },
        { it.timestamp },
        { it.id },
    )
    val ids = LinkedHashSet<String>()
    fun isProtocol(id: String) =
        id.startsWith(Constants.TOOL_MSG_PREFIX) || id.startsWith(Constants.RESULT_MSG_PREFIX)
    fun walk(root: CompactMessageGraphRow, runId: String) {
        if (root.runId != runId || !ids.add(root.id)) return
        children[root.id].orEmpty()
            .filter { isProtocol(it.id) && it.runId == runId }
            .sortedWith(order)
            .forEach { child -> walk(child, runId) }
    }
    for (id in ancestorIds) {
        val row = byId[id] ?: continue
        if (isProtocol(row.id)) {
            ids += row.id
            continue
        }
        children[row.id].orEmpty()
            .filter { it.runId == row.runId && it.id.startsWith(Constants.TOOL_MSG_PREFIX) }
            .sortedWith(order)
            .forEach { child -> walk(child, row.runId) }
    }
    return ids.toList()
}

internal suspend fun hydrateMessagesByIds(
    ids: List<String>,
    loadEntities: suspend (List<String>) -> List<MessageEntity>,
    includeStoredTranscriptions: Boolean,
    chunkSize: Int = 8,
): Map<String, ChatMessage> {
    if (ids.isEmpty()) return emptyMap()
    val out = LinkedHashMap<String, ChatMessage>(ids.size)
    ids.distinct().chunked(chunkSize.coerceAtLeast(1)).forEach { chunk ->
        projectHydratedEntities(loadEntities(chunk), includeStoredTranscriptions)
            .forEach { message -> out[message.id] = message }
    }
    return out
}

internal fun compactTurnsNeedBodyHydration(turns: List<CompactTurn>): Boolean =
    turns.any { turn ->
        turn.messages.any { message ->
            message.text.isEmpty() &&
                message.thoughts.isNullOrEmpty() &&
                message.toolCall == null &&
                message.segments.isNullOrEmpty()
        }
    }

internal fun replaceHydratedTurns(
    turns: List<CompactTurn>,
    projectedById: Map<String, ChatMessage>,
): List<CompactTurn> = turns.map { turn ->
    turn.copy(messages = turn.messages.map { message -> projectedById[message.id] ?: message })
}

internal fun pathDigestFromStored(
    stored: String?,
    rev: Int,
): PathDigest? {
    val body = stored?.trim().orEmpty()
    if (body.isEmpty()) return null
    val chapters = parseResidencyChapters(body)
    return PathDigest(
        body = formatResidencyChaptersForWire(chapters),
        rev = rev,
        chapters = chapters,
    )
}

internal fun newestBatchedDigestAnchorId(
    path: List<ChatMessage>,
    hasDigest: (anchorId: String) -> Boolean,
): Pair<String, Int>? {
    var bestId: String? = null
    var bestRev = Int.MIN_VALUE
    for (turn in CompactTurnIndexer.indexDialogueTurns(path)) {
        if (!turn.batched || !hasDigest(turn.anchorId)) continue
        if (turn.digestRev >= bestRev) {
            bestRev = turn.digestRev
            bestId = turn.anchorId
        }
    }
    val id = bestId ?: return null
    return id to bestRev.coerceAtLeast(0)
}

internal suspend fun loadNewestPathDigest(
    path: List<ChatMessage>,
    graphById: Map<String, CompactMessageGraphRow>,
    readDigest: suspend (String) -> String?,
): PathDigest? {
    val (anchorId, rev) = newestBatchedDigestAnchorId(path) { id ->
        graphById[id]?.hasCompactDigest == true
    } ?: return null
    return pathDigestFromStored(readDigest(anchorId), rev)
}

internal fun pathWithDigestBody(
    path: List<ChatMessage>,
    digest: PathDigest?,
    anchorId: String?,
): List<ChatMessage> {
    if (digest == null || anchorId.isNullOrBlank()) return path
    return path.map { message ->
        if (message.id == anchorId) {
            message.copy(compactDigest = digest.body, compactDigestRev = digest.rev)
        } else {
            message
        }
    }
}

internal fun timeBandSurvivorIds(
    path: List<ChatMessage>,
    retainTurns: Int,
): List<String> =
    applyTimeBandProjection(path, retainTurns).mapNotNull { message ->
        val id = message.id
        when {
            isContextProjectionMessageId(id) -> null
            id.startsWith(Constants.COMPACT_MSG_PREFIX) -> null
            else -> id
        }
    }

internal fun mergeHydratedMessages(
    path: List<ChatMessage>,
    hydratedById: Map<String, ChatMessage>,
): List<ChatMessage> = path.map { message -> hydratedById[message.id] ?: message }

internal fun projectHydratedEntities(
    entities: List<MessageEntity>,
    includeStoredTranscriptions: Boolean,
): List<ChatMessage> = projectGenerationStatusesForApi(
    projectProviderMessages(entities, includeStoredTranscriptions),
)

internal suspend fun hydrateCompactTurns(
    turns: List<CompactTurn>,
    loadEntities: suspend (List<String>) -> List<MessageEntity>,
    includeStoredTranscriptions: Boolean = false,
): List<CompactTurn> {
    if (turns.isEmpty()) return turns
    val ids = turns.flatMap { turn -> turn.messages.map { it.id } }
    if (ids.isEmpty()) return turns
    val byId = loadEntities(ids).associateBy { it.id }
    val ordered = ids.mapNotNull { byId[it] }
    if (ordered.isEmpty()) return turns
    val projectedById = projectHydratedEntities(ordered, includeStoredTranscriptions)
        .associateBy { it.id }
    return replaceHydratedTurns(turns, projectedById)
}
