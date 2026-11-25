package com.newoether.agora.api.util

import com.newoether.agora.data.formatResidencyChaptersForWire
import com.newoether.agora.data.parseResidencyChapters
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.isContextCompact
import com.newoether.agora.model.isSuccessfulContextCompact
import com.newoether.agora.tool.CompactTurn
import com.newoether.agora.tool.CompactTurnIndexer
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal const val CONTEXT_DIGEST_TYPE = "context_digest"
internal const val CONTEXT_DIGEST_ID_PREFIX = "context_digest_"

/** Target length for the digest body (prompt + soft guidance), ~3k tokens. */
internal const val CONTEXT_DIGEST_MAX_CHARS = 12_000

@Serializable
internal data class ContextDigestNote(
    val id: String,
    val summary: String = "",
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class ContextDigestPayload(
    val type: String = CONTEXT_DIGEST_TYPE,
    val rev: Int,
    val digest: String,
    /** Present only on old payloads; working-model JSON omits this. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val notes: List<ContextDigestNote> = emptyList(),
)

internal data class PathDigest(
    val body: String,
    val rev: Int,
    val chapters: List<String> = parseResidencyChapters(body),
)

private val contextDigestJson = Json {
    prettyPrint = true
    encodeDefaults = true
    ignoreUnknownKeys = true
}

/** `batch_` + UUID as issued by CompactBatchPlanner. */
private val BATCH_ID_PATTERN = Regex(
    """batch_[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""",
)

internal fun encodeContextDigestJson(
    rev: Int,
    digest: String,
): String = contextDigestJson.encodeToString(
    ContextDigestPayload(
        type = CONTEXT_DIGEST_TYPE,
        rev = rev,
        digest = digest,
    ),
)

/** Newest successful digest on the path, if any. */
internal fun resolvePathDigest(messages: List<ChatMessage>): PathDigest? {
    var best: PathDigest? = null
    for (turn in CompactTurnIndexer.indexDialogueTurns(messages)) {
        val stored = turn.digest?.trim().orEmpty()
        if (!turn.batched || stored.isEmpty()) continue
        val current = best
        if (current == null || turn.digestRev >= current.rev) {
            val chapters = parseResidencyChapters(stored)
            best = PathDigest(
                body = formatResidencyChaptersForWire(chapters),
                rev = turn.digestRev,
                chapters = chapters,
            )
        }
    }
    return best
}

internal fun uniqueBatchNotes(turns: List<CompactTurn>): List<ContextDigestNote> {
    val seen = mutableSetOf<String>()
    val notes = mutableListOf<ContextDigestNote>()
    for (turn in turns) {
        val id = turn.batchId?.takeIf { turn.batched } ?: continue
        if (seen.add(id)) {
            notes += ContextDigestNote(id = id, summary = turn.summary.orEmpty())
        }
    }
    return notes
}

/**
 * Drop `batch_<uuid>` tokens that are not in [allowedIds]. Blank after stripping is a failed update.
 */
internal fun stripIllegalBatchIdCites(digest: String, allowedIds: Set<String>): String =
    BATCH_ID_PATTERN.replace(digest) { match ->
        if (match.value in allowedIds) match.value else ""
    }

/** Closed-set ids that never appear in [digest]. Empty closed set is always covered. */
internal fun missingClosedSetBatchIdCites(digest: String, allowedIds: Set<String>): Set<String> =
    allowedIds.filterNot { it in digest }.toSet()

internal fun illegalBatchIdCites(digest: String, allowedIds: Set<String>): Set<String> =
    BATCH_ID_PATTERN.findAll(digest).map { it.value }.filterNot { it in allowedIds }.toSet()

internal fun formatContextDigestForReading(text: String): String? {
    val payload = runCatching {
        contextDigestJson.decodeFromString<ContextDigestPayload>(text.trim())
    }.getOrNull() ?: return null
    if (payload.type != CONTEXT_DIGEST_TYPE || payload.digest.isBlank()) return null
    return payload.digest.trim()
}

internal fun buildDigestUserPrompt(
    oldDigest: String?,
    closedSet: List<Pair<String, String>>,
    currentBatchOriginals: String?,
    activeMemory: String? = null,
    promptPersona: String? = null,
): String = buildString {
    append("现在根据下面材料改写归档索引。")
    append("\n\n[上一份 Digest]\n")
    val prior = oldDigest?.trim().orEmpty()
    if (prior.isEmpty()) {
        append("（空）\n")
    } else {
        append(prior)
        append('\n')
    }
    append("\n[闭集 batchId — 每个号都必须完整出现在正文中；禁止截断或加省略号]\n")
    if (closedSet.isEmpty()) {
        append("（无）\n")
    } else {
        closedSet.forEach { (batchId, summary) ->
            append("\nbatchId=")
            append(batchId)
            append(":\n")
            append(summary.trim())
            append('\n')
        }
    }
    val originals = currentBatchOriginals?.trim().orEmpty()
    if (originals.isNotEmpty()) {
        append("\n[本批原文]\n")
        append(originals)
        append('\n')
    }
    val memory = activeMemory?.trim().orEmpty()
    if (memory.isNotEmpty()) {
        append("\n[用户活跃记忆 — 不是要整合的内容]\n")
        append("只读词汇表，不要写进 Digest。\n")
        append(memory)
        append('\n')
    }
    val persona = promptPersona?.trim().orEmpty()
    if (persona.isNotEmpty()) {
        append("\n[用户人设（默认提示词）— 只读背景]\n")
        append(persona)
        append('\n')
    }
    append("\nDigest 正文不超过 ")
    append(CONTEXT_DIGEST_MAX_CHARS)
    append(" 字。不要输出本节标题。不要写「原位改写」。\n")
}

/**
 * One `context_digest_*` user row for the folded prefix. Body is plain 驻留 text
 * (not JSON). [PathDigest.rev] only names the synthetic id; it is not sent on the wire.
 */
internal fun applyDigestProjection(
    messages: List<ChatMessage>,
    retainTurns: Int,
    digest: PathDigest,
): List<ChatMessage> {
    val withoutFailedCapsules = messages.filterNot {
        it.isContextCompact() && !it.isSuccessfulContextCompact()
    }
    val path = withoutFailedCapsules.filterNot { it.isContextCompact() }
    val turns = CompactTurnIndexer.finishedDialogueTurns(path)
    if (turns.isEmpty()) return path

    val retain = retainTurns.coerceAtLeast(0)
    val retainFrom = (turns.size - retain).coerceAtLeast(0)
    val result = mutableListOf<ChatMessage>()
    var digestEmitted = false
    var digestTimestamp = 0L

    fun emitDigest() {
        if (digestEmitted) return
        result += ChatMessage(
            id = "$CONTEXT_DIGEST_ID_PREFIX${digest.rev}",
            text = digest.body,
            participant = Participant.USER,
            status = MessageStatus.SUCCESS,
            timestamp = digestTimestamp,
        )
        digestEmitted = true
    }

    turns.forEachIndexed { index, turn ->
        val inRetain = index >= retainFrom
        if (inRetain || !turn.batched) {
            result += turn.messages
            return@forEachIndexed
        }
        if (!digestEmitted) {
            digestTimestamp = turn.messages.firstOrNull()?.timestamp ?: 0L
            emitDigest()
        }
    }
    val lastCoveredId = turns.last().messages.lastOrNull()?.id
    if (lastCoveredId != null) {
        val lastCoveredIndex = path.indexOfLast { it.id == lastCoveredId }
        if (lastCoveredIndex in 0 until path.lastIndex) {
            result += path.subList(lastCoveredIndex + 1, path.size)
        }
    }
    return result
}
