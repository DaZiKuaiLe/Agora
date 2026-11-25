package com.newoether.agora.data.repository

import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.ChatUiMessageDao
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ConversationRepositoryOpenPathTest {
    @Test
    fun `stuck-state repair does not materialize the complete conversation graph`() = runTest {
        val dao = mockk<ChatDao>(relaxed = true)
        val repository = ConversationRepository(dao)

        repository.fixStuckMessages("conversation")

        coVerify(exactly = 0) { dao.upsertMessage(any()) }
        coVerify(exactly = 1) { dao.stopStuckMessagesForConversation("conversation") }
    }

    @Test
    fun `deleting a conversation does not materialize message bodies`() = runTest {
        val dao = mockk<ChatDao>(relaxed = true)
        val uiDao = mockk<ChatUiMessageDao>(relaxed = true)
        val repository = ConversationRepository(dao, uiDao)

        repository.deleteConversation("conversation")

        coVerify(exactly = 0) { dao.getMessagesByIds(any()) }
        coVerify(exactly = 1) {
            uiDao.getMessageAttachmentReferencesForConversationPage("conversation", null, any())
        }
        coVerify(exactly = 1) { dao.deleteEmbeddingsByConversation("conversation") }
        coVerify(exactly = 1) { dao.deleteMessagesByConversation("conversation") }
        coVerify(exactly = 1) { dao.deleteConversation("conversation") }
    }
}
