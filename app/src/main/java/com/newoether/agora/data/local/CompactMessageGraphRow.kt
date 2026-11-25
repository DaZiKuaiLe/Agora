package com.newoether.agora.data.local

import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant

/**
 * Compact path identity: enough to walk branches, index turns, and decide which bodies
 * to hydrate. Text, thoughts, tool JSON, and residency bodies stay on disk.
 */
data class CompactMessageGraphRow(
    val id: String,
    val conversationId: String,
    val parentId: String?,
    val participant: Participant,
    val status: MessageStatus,
    val timestamp: Long,
    val runId: String,
    val runSequence: Long,
    val modelName: String?,
    val consumedAtPass: Int?,
    val turnId: String?,
    val compactLocked: Boolean,
    val compactBatchId: String?,
    val compactDigestRev: Int,
    val hasCompactDigest: Boolean,
)
