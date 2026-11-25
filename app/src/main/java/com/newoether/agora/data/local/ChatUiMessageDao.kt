package com.newoether.agora.data.local

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatUiMessageDao {
    @Query(
        """
        SELECT id, parentId, status, compactDigestRev, compactBatchId, turnId,
               participant, timestamp, runId, tokenCount
        FROM messages
        WHERE conversationId = :conversationId
        ORDER BY timestamp ASC
        """,
    )
    fun observeUiMessageGraph(conversationId: String): Flow<List<UiMessageGraphRow>>

    @Query(
        """
        SELECT
            id,
            conversationId,
            parentId,
            CASE
                WHEN substr(id, 1, 5) = 'tool_'
                    OR substr(id, 1, 7) = 'result_'
                    OR substr(id, 1, 7) = 'bgproc_' THEN ''
                ELSE text
            END AS text,
            CASE
                WHEN substr(id, 1, 5) = 'tool_'
                    OR substr(id, 1, 7) = 'result_'
                    OR substr(id, 1, 7) = 'bgproc_' THEN '[]'
                ELSE images
            END AS images,
            NULL AS thoughts,
            CASE
                WHEN substr(id, 1, 5) = 'tool_'
                    OR substr(id, 1, 7) = 'result_' THEN NULL
                ELSE thoughtTitle
            END AS thoughtTitle,
            CASE
                WHEN substr(id, 1, 5) = 'tool_'
                    OR substr(id, 1, 7) = 'result_' THEN 0
                ELSE tokenCount
            END AS tokenCount,
            CASE
                WHEN substr(id, 1, 5) = 'tool_'
                    OR substr(id, 1, 7) = 'result_' THEN NULL
                ELSE inputTokenCount
            END AS inputTokenCount,
            CASE
                WHEN substr(id, 1, 5) = 'tool_'
                    OR substr(id, 1, 7) = 'result_' THEN NULL
                ELSE cachedInputTokenCount
            END AS cachedInputTokenCount,
            CASE
                WHEN substr(id, 1, 5) = 'tool_'
                    OR substr(id, 1, 7) = 'result_' THEN NULL
                ELSE uncachedInputTokenCount
            END AS uncachedInputTokenCount,
            CASE
                WHEN substr(id, 1, 5) = 'tool_'
                    OR substr(id, 1, 7) = 'result_' THEN NULL
                ELSE outputTokenCount
            END AS outputTokenCount,
            CASE
                WHEN substr(id, 1, 5) = 'tool_'
                    OR substr(id, 1, 7) = 'result_' THEN NULL
                ELSE reasoningTokenCount
            END AS reasoningTokenCount,
            status,
            participant,
            timestamp,
            CASE
                WHEN substr(id, 1, 5) = 'tool_'
                    OR substr(id, 1, 7) = 'result_' THEN NULL
                ELSE thoughtTimeMs
            END AS thoughtTimeMs,
            modelName,
            CASE
                WHEN substr(id, 1, 5) = 'tool_'
                    OR substr(id, 1, 7) = 'result_' THEN NULL
                ELSE toolCallJson
            END AS toolCallJson,
            CASE
                WHEN substr(id, 1, 5) = 'tool_'
                    OR substr(id, 1, 7) = 'result_' THEN NULL
                ELSE attachmentMeta
            END AS attachmentMeta,
            runId,
            runSequence,
            consumedAtPass,
            turnId,
            compactLocked,
            compactBatchId,
            compactSummary,
            NULL AS compactDigest,
            compactDigestRev,
            NULL AS integrateText
        FROM messages
        WHERE conversationId = :conversationId
          AND id IN (:ids)
        ORDER BY timestamp ASC
        """,
    )
    suspend fun getUiMessagesByIds(
        conversationId: String,
        ids: List<String>,
    ): List<MessageEntity>

    @Query(
        """
        SELECT
            id,
            conversationId,
            parentId,
            participant,
            status,
            timestamp,
            runId,
            runSequence,
            modelName,
            consumedAtPass,
            turnId,
            compactLocked,
            compactBatchId,
            compactDigestRev,
            CASE
                WHEN compactDigest IS NOT NULL AND compactDigest != '' THEN 1
                ELSE 0
            END AS hasCompactDigest
        FROM messages
        WHERE conversationId = :conversationId
        ORDER BY timestamp ASC
        """,
    )
    suspend fun getCompactMessageGraph(conversationId: String): List<CompactMessageGraphRow>

    @Query("SELECT compactDigest FROM messages WHERE id = :messageId")
    suspend fun getCompactDigest(messageId: String): String?

    @Query(
        """
        SELECT id, images, attachmentMeta
        FROM messages
        WHERE conversationId = :conversationId
          AND (:afterId IS NULL OR id > :afterId)
          AND (
              (images != '' AND images != '[]')
              OR (attachmentMeta IS NOT NULL AND attachmentMeta != '')
          )
        ORDER BY id
        LIMIT :limit
        """,
    )
    suspend fun getMessageAttachmentReferencesForConversationPage(
        conversationId: String,
        afterId: String?,
        limit: Int,
    ): List<MessageAttachmentReference>

    @Query(
        """
        SELECT id, images, attachmentMeta
        FROM messages
        WHERE id IN (:ids)
          AND (
              (images != '' AND images != '[]')
              OR (attachmentMeta IS NOT NULL AND attachmentMeta != '')
          )
        """,
    )
    suspend fun getMessageAttachmentReferencesByIds(
        ids: List<String>,
    ): List<MessageAttachmentReference>
}
