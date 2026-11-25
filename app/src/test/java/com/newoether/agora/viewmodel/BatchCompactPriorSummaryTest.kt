package com.newoether.agora.viewmodel

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.tool.CompactTurn
import com.newoether.agora.util.Constants
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchCompactPriorSummaryTest {
    @Test
    fun summaryPromptIsOnlyNewOriginals() {
        val members = listOf(turn("t9", null, null), turn("t10", null, null))
        val prompt = BatchCompactEngine.buildSummaryUserPrompt(members)
        assertTrue(prompt.contains("--- turn t9 ---"))
        assertTrue(prompt.contains("--- turn t10 ---"))
        assertTrue(prompt.contains("[User]"))
        assertFalse(prompt.contains("Prior facts"))
        assertFalse(prompt.contains("[上一次的整合文本]"))
        assertFalse(prompt.contains("[用户活跃记忆]"))
        assertFalse(prompt.contains("[上一份驻留]"))
        assertFalse(prompt.contains("[冻结说明]"))
        assertFalse(prompt.contains("活跃记忆"))
    }

    @Test
    fun pathHasCompactArtifactsDetectsDigestBatchesAndCapsules() {
        assertFalse(BatchCompactEngine.pathHasCompactArtifacts(emptyList()))
        assertFalse(
            BatchCompactEngine.pathHasCompactArtifacts(
                listOf(
                    ChatMessage(
                        id = "u1",
                        text = "hello",
                        participant = Participant.USER,
                        status = MessageStatus.SUCCESS,
                    ),
                    ChatMessage(
                        id = "a1",
                        text = "hi",
                        participant = Participant.MODEL,
                        status = MessageStatus.SUCCESS,
                        parentId = "u1",
                        turnId = "t1",
                    ),
                ),
            ),
        )
        assertTrue(
            BatchCompactEngine.pathHasCompactArtifacts(
                listOf(
                    ChatMessage(
                        id = "u2",
                        text = "hello",
                        participant = Participant.USER,
                        status = MessageStatus.SUCCESS,
                    ),
                    ChatMessage(
                        id = "a2",
                        text = "hi",
                        participant = Participant.MODEL,
                        status = MessageStatus.SUCCESS,
                        parentId = "u2",
                        turnId = "t2",
                        compactBatchId = "summary",
                    ),
                ),
            ),
        )
        assertTrue(
            BatchCompactEngine.pathHasCompactArtifacts(
                listOf(
                    ChatMessage(
                        id = "${Constants.COMPACT_MSG_PREFIX}legacy",
                        text = "old capsule",
                        participant = Participant.MODEL,
                        status = MessageStatus.SUCCESS,
                    ),
                ),
            ),
        )
    }

    private fun turn(
        turnId: String,
        batchId: String?,
        summary: String?,
    ): CompactTurn {
        val user = ChatMessage(
            id = "u_$turnId",
            text = "user $turnId",
            participant = Participant.USER,
            status = MessageStatus.SUCCESS,
        )
        val assistant = ChatMessage(
            id = "a_$turnId",
            text = "assistant $turnId",
            participant = Participant.MODEL,
            status = MessageStatus.SUCCESS,
            parentId = user.id,
            turnId = turnId,
            compactBatchId = batchId,
            compactSummary = summary,
        )
        return CompactTurn(
            turnId = turnId,
            locked = false,
            batchId = batchId,
            summary = summary,
            messages = listOf(user, assistant),
            anchorId = assistant.id,
        )
    }
}
