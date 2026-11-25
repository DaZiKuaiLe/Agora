package com.newoether.agora.data.local

import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant

/** Identity-only UI graph row. Bodies and tool JSON stay on disk until a row actually changes. */
data class UiMessageGraphRow(
    val id: String,
    val parentId: String?,
    val status: MessageStatus,
    val compactDigestRev: Int,
    val compactBatchId: String?,
    val turnId: String?,
    val participant: Participant = Participant.USER,
    val timestamp: Long = 0L,
    val runId: String? = null,
    val tokenCount: Int = 0,
)
