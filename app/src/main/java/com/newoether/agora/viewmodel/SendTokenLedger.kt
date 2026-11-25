package com.newoether.agora.viewmodel

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.tool.CompactTurn
import com.newoether.agora.tool.CompactTurnIndexer

/**
 * Persisted next-send occupancy. The context bar reads this; it does not rebuild the
 * provider path on every message. A seated row is trusted until [configKey] changes.
 *
 * [prefixTokens] is uncompressed originals outside the retain window (the compact trigger),
 * not projected occupancy.
 */
data class SendTokenCache(
    val tokens: Int = 0,
    val countedTurnId: String? = null,
    val budget: Int = 0,
    val retainTurns: Int = 0,
    val fixedCost: Int = 0,
    val configKey: String? = null,
    val prefixTokens: Int = 0,
) {
    fun isSeated(): Boolean = !countedTurnId.isNullOrBlank() && !configKey.isNullOrBlank()

    fun canServe(configKey: String, budget: Int, retainTurns: Int): Boolean =
        isSeated() &&
            this.configKey == configKey &&
            this.budget == budget &&
            this.retainTurns == retainTurns
}

internal fun sendTokenConfigKey(
    modelId: String,
    budget: Int,
    retainTurns: Int,
    toolConfigurationKey: Any,
): String = listOf(modelId, budget, retainTurns, toolConfigurationKey.hashCode()).joinToString("|")

internal fun applyCompletedTurnTokens(previous: Int, turnTokens: Int): Int =
    (previous.toLong() + turnTokens.coerceAtLeast(0).toLong())
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()

internal fun sendTokenMismatch(
    persisted: Int,
    computed: Int,
): Boolean = persisted != computed

/**
 * Identity of the seated send path: last finished turn plus compact revisions.
 * Message bodies are ignored so streaming checkpoints do not move the cursor.
 */
internal fun sendLedgerCursor(messages: List<ChatMessage>): String {
    val turns = CompactTurnIndexer.finishedDialogueTurns(messages)
    val lastTurnId = turns.lastOrNull()?.turnId ?: "seated"
    return "$lastTurnId|${compactSignature(turns)}"
}

internal fun compactSignature(turns: List<CompactTurn>): Int {
    var compactSig = 0
    for (turn in turns) {
        compactSig = compactSig * 31 + turn.digestRev
        turn.batchId?.let { compactSig = compactSig * 31 + it.hashCode() }
    }
    return compactSig
}

/**
 * True when [sendLedgerCursor] cannot change. Text, thoughts, and segments are ignored so a
 * streaming snapshot does not regroup finished turns.
 */
internal fun sameSendLedgerStructure(
    previous: List<ChatMessage>,
    next: List<ChatMessage>,
): Boolean {
    if (previous.size != next.size) return false
    for (index in previous.indices) {
        val before = previous[index]
        val after = next[index]
        if (before === after) continue
        if (
            before.id != after.id ||
            before.parentId != after.parentId ||
            before.participant != after.participant ||
            before.status != after.status ||
            before.turnId != after.turnId ||
            before.compactDigestRev != after.compactDigestRev ||
            before.compactBatchId != after.compactBatchId ||
            before.compactLocked != after.compactLocked
        ) {
            return false
        }
    }
    return true
}

/** Remembers the last cursor until message identity, status, or compact markers change. */
internal class SendLedgerCursorCache {
    private var previous: List<ChatMessage> = emptyList()
    private var cursor: String = "seated|0"
    private var ready: Boolean = false

    fun cursor(messages: List<ChatMessage>): String {
        if (ready && sameSendLedgerStructure(previous, messages)) return cursor
        cursor = sendLedgerCursor(messages)
        previous = messages
        ready = true
        return cursor
    }
}

internal fun parseSendLedgerCursor(cursor: String?): Pair<String, Int>? {
    if (cursor.isNullOrBlank()) return null
    val sep = cursor.lastIndexOf('|')
    if (sep <= 0 || sep == cursor.lastIndex) return null
    val signature = cursor.substring(sep + 1).toIntOrNull() ?: return null
    return cursor.substring(0, sep) to signature
}

internal sealed class SendTokenAdvance {
    data class Seated(val cache: SendTokenCache) : SendTokenAdvance()
    data class Incremental(val cache: SendTokenCache) : SendTokenAdvance()
    data object NeedsFullRecompute : SendTokenAdvance()
}

/**
 * Memory only applies a one-turn delta. A cursor or config mismatch keeps the saved number.
 * Occupancy never rereads the conversation to repair itself.
 */
internal fun advanceSendTokenCache(
    cache: SendTokenCache,
    configKey: String,
    tokenBudget: Int,
    retainTurns: Int,
    nextCursor: String,
    turns: List<CompactTurn>,
    estimateTurn: (CompactTurn) -> Int,
    fixedCost: Int,
): SendTokenAdvance {
    if (cache.canServe(configKey, tokenBudget, retainTurns) && cache.countedTurnId == nextCursor) {
        return SendTokenAdvance.Seated(cache)
    }
    if (!cache.canServe(configKey, tokenBudget, retainTurns)) {
        return SendTokenAdvance.NeedsFullRecompute
    }
    val previous = parseSendLedgerCursor(cache.countedTurnId) ?: return SendTokenAdvance.NeedsFullRecompute
    val next = parseSendLedgerCursor(nextCursor) ?: return SendTokenAdvance.NeedsFullRecompute
    if (previous.second != next.second) return SendTokenAdvance.NeedsFullRecompute
    val previousIndex = turns.indexOfLast { it.turnId == previous.first }
    val nextIndex = turns.indexOfLast { it.turnId == next.first }
    if (previousIndex < 0 || nextIndex != previousIndex + 1) {
        return SendTokenAdvance.NeedsFullRecompute
    }
    var tokens = applyCompletedTurnTokens(cache.tokens, estimateTurn(turns[nextIndex]))
    var prefixTokens = cache.prefixTokens
    if (nextIndex >= retainTurns) {
        val leaving = turns[nextIndex - retainTurns]
        val leavingTokens = estimateTurn(leaving).coerceAtLeast(0)
        // Unbatched originals are still sent after they leave the retain window.
        if (leaving.batched) {
            tokens = (tokens.toLong() - leavingTokens.toLong())
                .coerceIn(0L, Int.MAX_VALUE.toLong())
                .toInt()
        } else {
            prefixTokens = applyCompletedTurnTokens(prefixTokens, leavingTokens)
        }
    }
    return SendTokenAdvance.Incremental(
        cache.copy(
            tokens = tokens,
            countedTurnId = nextCursor,
            budget = tokenBudget,
            retainTurns = retainTurns,
            fixedCost = fixedCost,
            configKey = configKey,
            prefixTokens = prefixTokens,
        ),
    )
}

/**
 * Interactive send trusts a seated ledger. Unseated or jumped history is not a reason to
 * reload the provider path; context detail remains the audit.
 */
internal fun resolveSendLedgerForInteractive(
    cache: SendTokenCache,
    tokenBudget: Int,
    retainTurns: Int,
    nextCursor: String,
    turns: List<CompactTurn>,
    estimateTurn: (CompactTurn) -> Int,
): SendTokenAdvance {
    if (!cache.isSeated() || cache.budget != tokenBudget || cache.retainTurns != retainTurns) {
        return SendTokenAdvance.NeedsFullRecompute
    }
    val configKey = cache.configKey ?: return SendTokenAdvance.NeedsFullRecompute
    return advanceSendTokenCache(
        cache = cache,
        configKey = configKey,
        tokenBudget = tokenBudget,
        retainTurns = retainTurns,
        nextCursor = nextCursor,
        turns = turns,
        estimateTurn = estimateTurn,
        fixedCost = cache.fixedCost,
    )
}

/** Compact trigger: uncompressed prefix originals plus frozen tool/system cost. */
internal fun automaticCompactNeededFromLedger(
    cache: SendTokenCache,
    compactThreshold: Int,
): Boolean {
    if (!cache.isSeated()) return false
    val prefix = cache.prefixTokens.coerceAtLeast(0)
    if (prefix <= 0) return false
    val threshold = compactThreshold.coerceAtLeast(1)
    return prefix.toLong() + cache.fixedCost.coerceAtLeast(0).toLong() >= threshold.toLong()
}

/**
 * What the context bar shows. A mismatch keeps [cache]'s number. Only an exact cursor match
 * or one newly finished turn may change it, and neither path reads message bodies.
 */
internal data class OccupancyDisplay(
    val tokens: Int,
    val fixedCost: Int,
    val countedTurnId: String?,
    val persist: SendTokenCache? = null,
)

internal fun refreshDisplayedOccupancy(
    cache: SendTokenCache,
    configKey: String,
    tokenBudget: Int,
    retainTurns: Int,
    ledgerCursor: String,
    onLastPage: Boolean,
    turns: List<CompactTurn>,
    estimateTurn: (CompactTurn) -> Int,
): OccupancyDisplay {
    if (!onLastPage || !cache.canServe(configKey, tokenBudget, retainTurns)) {
        return OccupancyDisplay(cache.tokens, cache.fixedCost, cache.countedTurnId)
    }
    val advanced = advanceSendTokenCache(
        cache = cache,
        configKey = configKey,
        tokenBudget = tokenBudget,
        retainTurns = retainTurns,
        nextCursor = ledgerCursor,
        turns = turns,
        estimateTurn = estimateTurn,
        fixedCost = cache.fixedCost,
    )
    return when (advanced) {
        is SendTokenAdvance.Seated ->
            OccupancyDisplay(advanced.cache.tokens, advanced.cache.fixedCost, advanced.cache.countedTurnId)
        is SendTokenAdvance.Incremental ->
            OccupancyDisplay(
                tokens = advanced.cache.tokens,
                fixedCost = advanced.cache.fixedCost,
                countedTurnId = advanced.cache.countedTurnId,
                persist = advanced.cache,
            )
        SendTokenAdvance.NeedsFullRecompute ->
            OccupancyDisplay(cache.tokens, cache.fixedCost, cache.countedTurnId)
    }
}

/** Cursor the bar will compute after this compact commit, from turn identity only. */
internal fun cursorAfterCompactCommit(
    turns: List<CompactTurn>,
    clearExistingBatches: Boolean,
    coveredTurnIds: Set<String>,
    digestTurnId: String?,
    digestRev: Int,
    batchId: String,
): String {
    val projected = turns.map { turn ->
        val cleared = if (clearExistingBatches && turn.batched) {
            turn.copy(batchId = null, summary = null, digest = null, digestRev = 0)
        } else {
            turn
        }
        if (cleared.turnId !in coveredTurnIds) return@map cleared
        cleared.copy(
            batchId = batchId,
            summary = null,
            digestRev = if (cleared.turnId == digestTurnId) digestRev else cleared.digestRev,
        )
    }
    val lastTurnId = projected.lastOrNull()?.turnId ?: "seated"
    return "$lastTurnId|${compactSignature(projected)}"
}

/**
 * Occupancy written in the same commit as the new digest: the summary, the turns still sent
 * as originals, and the fixed prompt cost already on the ledger. The old occupancy is not
 * reused. The uncompressed-prefix trigger is cleared because that prefix is now in the summary.
 */
internal fun cacheAfterCompactCommit(
    previous: SendTokenCache,
    summaryTokens: Int,
    keptOriginalTokens: Int,
    nextCursor: String,
): SendTokenCache {
    val tokens = (
        summaryTokens.coerceAtLeast(0).toLong() +
            keptOriginalTokens.coerceAtLeast(0).toLong() +
            previous.fixedCost.coerceAtLeast(0).toLong()
        ).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    return previous.copy(
        tokens = tokens,
        countedTurnId = nextCursor,
        prefixTokens = 0,
    )
}

/** Panel audit overwrites the stored send ledger with the number it just measured. */
internal fun cacheCorrectedByContextDetail(
    previous: SendTokenCache,
    sentTokens: Int,
    prefixTokens: Int,
    fixedCost: Int,
    ledgerCursor: String,
    tokenBudget: Int,
    retainTurns: Int,
    configKey: String,
): SendTokenCache = previous.copy(
    tokens = sentTokens.coerceAtLeast(0),
    countedTurnId = ledgerCursor.ifBlank { previous.countedTurnId },
    budget = tokenBudget,
    retainTurns = retainTurns,
    fixedCost = fixedCost.coerceAtLeast(0),
    configKey = configKey.ifBlank { previous.configKey },
    prefixTokens = prefixTokens.coerceAtLeast(0),
)
