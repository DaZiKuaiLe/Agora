package com.newoether.agora.viewmodel

import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.local.CompactMessageGraphRow
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.local.RunEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.model.AttachmentItem
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunEndReason
import com.newoether.agora.model.RunStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ConversationForkShareServiceTest {
    @Test
    fun `share text excludes thinking and tool payloads while keeping public content`() {
        val messages = listOf(
            message(
                id = "user",
                text = "Tell me why",
                participant = Participant.USER,
                sequence = 0,
                attachmentMeta = AttachmentMeta(
                    listOf(AttachmentItem(type = "file", fileName = "notes.pdf")),
                ),
            ),
            message(
                id = "structured-model",
                text = "Fallback answer",
                participant = Participant.MODEL,
                sequence = 1,
                segments = listOf(
                    MessageSegment(type = "thought", content = "Private structured reasoning"),
                    MessageSegment(
                        type = "tool",
                        toolName = "web_search",
                        toolArgs = """{"query":"private query"}""",
                        toolResult = "Private tool result",
                    ),
                    MessageSegment(type = "answer", content = "Public structured answer"),
                    MessageSegment(type = "transcription", content = "Public transcription"),
                ),
            ),
            message(
                id = "legacy-model",
                text = "Public legacy answer",
                participant = Participant.MODEL,
                sequence = 2,
                thoughts = "Private legacy reasoning",
            ),
            message("error", "Public error", Participant.ERROR, 3),
            message("tool_hidden", "Synthetic protocol payload", Participant.MODEL, 4),
            message(
                id = "bgproc_exit",
                text = "【系统提示】\nprocess: 后台进程已结束\n\noutput:\nsecret-dump",
                participant = Participant.USER,
                sequence = 5,
            ),
        )

        val text = formatShareText("Explanation", messages)

        assertTrue(text.contains("## User\n\nTell me why"))
        assertTrue(text.contains("Attachments: notes.pdf"))
        assertTrue(text.contains("## Assistant\n\nPublic structured answer"))
        assertTrue(text.contains("## Transcription\n\nPublic transcription"))
        assertTrue(text.contains("## Assistant\n\nPublic legacy answer"))
        assertTrue(text.contains("## Error\n\nPublic error"))
        assertFalse(text.contains("## Thinking"))
        assertFalse(text.contains("Private structured reasoning"))
        assertFalse(text.contains("Private legacy reasoning"))
        assertFalse(text.contains("## Tool"))
        assertFalse(text.contains("web_search"))
        assertFalse(text.contains("private query"))
        assertFalse(text.contains("Private tool result"))
        assertFalse(text.contains("Synthetic protocol payload"))
        assertFalse(text.contains("secret-dump"))
        assertFalse(text.contains("后台进程已结束"))
    }

    @Test
    fun `fork hydrates only selected-branch bodies`() = runTest {
        val conversations = mockk<ConversationRepository>()
        val settings = mockk<SettingsRepository>(relaxed = true)
        every { settings.conversationSettings } returns MutableStateFlow(emptyMap())
        val user = message("user", "Hello", Participant.USER, 0)
        val model = message("model", "Hi", Participant.MODEL, 1)
        val offPath = message("off-path", "must not hydrate", Participant.MODEL, 2)
            .copy(parentId = "user")
        val modelOnPath = model.copy(parentId = "user")
        coEvery { conversations.getConversation("conversation") } returns ChatEntity(
            id = "conversation",
            title = "Source",
        )
        coEvery { conversations.getCompactMessageGraph("conversation") } returns listOf(
            user.toGraphRow(),
            modelOnPath.toGraphRow(),
            offPath.toGraphRow(),
        )
        coEvery { conversations.getRunsForConversationSnapshot("conversation") } returns
            listOf(completedRun())
        coEvery { conversations.restoreBranchSelections("conversation") } returns
            mapOf(null to "user", "user" to "model")
        coEvery {
            conversations.getMessagesByIds(match { ids -> ids.toSet() == setOf("user", "model") })
        } returns listOf(user, modelOnPath)
        coEvery { conversations.createForkGraph(any(), any(), any(), any()) } returns Unit
        val service = ConversationForkShareService(
            conversations = conversations,
            settings = settings,
            attachmentRoot = File(System.getProperty("java.io.tmpdir"), "agora-fork-test"),
        )

        val result = service.fork("conversation", throughMessageId = null)

        assertTrue(result is ConversationForkShareService.ForkResult.Success)
        coVerify(exactly = 1) {
            conversations.getMessagesByIds(match { ids -> ids.toSet() == setOf("user", "model") })
        }
        coVerify(exactly = 1) {
            conversations.createForkGraph(
                any(),
                any(),
                match { cloned -> cloned.none { it.text == "must not hydrate" } },
                match { mapping -> mapping.keys == setOf("user", "model") },
            )
        }
    }

    private fun completedRun() = RunEntity(
        id = "run",
        conversationId = "conversation",
        parentRunId = null,
        status = RunStatus.COMPLETED,
        activeSlot = null,
        startedAt = 1L,
        lastCheckpointAt = 2L,
        endedAt = 2L,
        endReason = RunEndReason.MODEL_COMPLETED,
    )

    private fun MessageEntity.toGraphRow() = CompactMessageGraphRow(
        id = id,
        conversationId = conversationId,
        parentId = parentId,
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
        hasCompactDigest = !compactDigest.isNullOrBlank(),
    )

    private fun message(
        id: String,
        text: String,
        participant: Participant,
        sequence: Long,
        thoughts: String? = null,
        segments: List<MessageSegment>? = null,
        attachmentMeta: AttachmentMeta? = null,
    ) = MessageEntity(
        id = id,
        conversationId = "conversation",
        text = text,
        thoughts = thoughts,
        status = MessageStatus.SUCCESS,
        participant = participant,
        timestamp = sequence,
        toolCallJson = segments?.let { Json.encodeToString(it) },
        attachmentMeta = attachmentMeta?.let { Json.encodeToString(it) },
        runId = "run",
        runSequence = sequence,
    )
}
