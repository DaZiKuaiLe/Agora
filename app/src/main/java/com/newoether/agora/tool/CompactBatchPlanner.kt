package com.newoether.agora.tool

import java.util.UUID

/**
 * Pure helpers for summary-style batch compression (see development-docs/context-compact-turn-archive.md).
 * Lock = 总结式压缩锁定: turn is excluded from batches; tool de-detailing still applies.
 */
object CompactBatchPlanner {
    const val DEFAULT_BATCH_SIZE = 8

    data class TurnRef(
        val turnId: String,
        val locked: Boolean,
        /** True once this turn belongs to a persisted summary batch. */
        val batched: Boolean = false,
    )

    data class BatchPlan(
        val batchId: String,
        val turnIds: List<String>,
        /** True when turnIds.size < batchSize (formal compress only; not for precompress). */
        val partial: Boolean,
    )

    /**
     * Split [turns] (oldest → newest, already excluding the trailing retain window) into batches.
     * Locked turns are hard barriers and never appear in [BatchPlan.turnIds].
     *
     * @param allowPartial when true (threshold / full recompact), trailing runs shorter than
     * [batchSize] become batches; when false (precompress), only full-size runs are returned.
     */
    fun planBatches(
        turns: List<TurnRef>,
        batchSize: Int = DEFAULT_BATCH_SIZE,
        allowPartial: Boolean,
        newBatchId: () -> String = { "batch_${UUID.randomUUID()}" },
    ): List<BatchPlan> {
        require(batchSize > 0)
        val plans = mutableListOf<BatchPlan>()
        val buffer = mutableListOf<String>()

        fun flush(forcePartial: Boolean) {
            if (buffer.isEmpty()) return
            val full = buffer.size >= batchSize
            if (!full && !forcePartial && !allowPartial) {
                buffer.clear()
                return
            }
            if (!full && !allowPartial) {
                buffer.clear()
                return
            }
            // Take chunks of at most batchSize; with allowPartial, leftover < batchSize is one plan.
            var i = 0
            while (i < buffer.size) {
                val remaining = buffer.size - i
                if (remaining >= batchSize) {
                    val slice = buffer.subList(i, i + batchSize).toList()
                    plans += BatchPlan(newBatchId(), slice, partial = false)
                    i += batchSize
                } else if (allowPartial) {
                    val slice = buffer.subList(i, buffer.size).toList()
                    plans += BatchPlan(newBatchId(), slice, partial = true)
                    break
                } else {
                    break
                }
            }
            buffer.clear()
        }

        for (turn in turns) {
            if (turn.batched) {
                flush(forcePartial = allowPartial)
                continue
            }
            if (turn.locked) {
                flush(forcePartial = allowPartial)
                continue
            }
            buffer += turn.turnId
            if (buffer.size >= batchSize && !allowPartial) {
                // Precompress path: emit as soon as full.
                flush(forcePartial = false)
            }
        }
        flush(forcePartial = allowPartial)
        return plans
    }

    /** Turns eligible for batching: drop the newest [retainCount] turns (retain window). */
    fun compressiblePrefix(
        turnsOldestFirst: List<TurnRef>,
        retainCount: Int,
    ): List<TurnRef> {
        if (turnsOldestFirst.isEmpty()) return emptyList()
        val keep = retainCount.coerceAtLeast(0)
        if (turnsOldestFirst.size <= keep) return emptyList()
        return turnsOldestFirst.dropLast(keep)
    }
}
