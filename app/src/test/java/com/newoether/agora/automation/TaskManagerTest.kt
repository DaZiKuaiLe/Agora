package com.newoether.agora.automation

import com.newoether.agora.data.local.ExecutionMessagePreview
import com.newoether.agora.data.local.TaskEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.TaskRepository
import com.newoether.agora.model.ChatConversation
import com.newoether.agora.model.MessageStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskManagerTest {
    @Test
    fun scheduledIncompleteTaskIsDisabledWithoutCallingModel() = runTest {
        val repository = mockk<TaskRepository>()
        val conversations = mockk<ConversationRepository>()
        val engine = mockk<TaskExecutionEngine>()
        var stored = task(prompt = "")
        every { repository.getAllTasks() } returns MutableStateFlow(listOf(stored))
        coEvery { repository.getTask(stored.id) } coAnswers { stored }
        coEvery { repository.upsertTask(any()) } coAnswers { stored = firstArg() }
        val manager = TaskManager(repository, conversations, engine, backgroundScope)

        val result = manager.executeById(stored.id, "execution", stored.nextRunAt)

        assertTrue(result is TaskManager.ExecutionResult.Skipped)
        assertFalse(stored.enabled)
        assertEquals(0L, stored.nextRunAt)
        coVerify(exactly = 0) { engine.runOnce(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun incompleteDraftIsNeverPersisted() = runTest {
        val repository = mockk<TaskRepository>()
        every { repository.getAllTasks() } returns MutableStateFlow(emptyList())
        coEvery { repository.upsertTask(any()) } returns Unit
        val manager = TaskManager(
            repository,
            mockk(),
            mockk(),
            backgroundScope,
        )

        manager.saveTask(task(name = "", prompt = "Prompt"))
        manager.saveTask(task(name = "Task", prompt = ""))

        coVerify(exactly = 0) { repository.upsertTask(any()) }
    }

    @Test
    fun busyConversationIsReturnedAsAReplaySafeDeferredOccurrence() = runTest {
        val repository = mockk<TaskRepository>()
        val conversations = mockk<ConversationRepository>()
        val engine = mockk<TaskExecutionEngine>()
        val stored = task()
        every { repository.getAllTasks() } returns MutableStateFlow(listOf(stored))
        coEvery { repository.getTask(stored.id) } returns stored
        coEvery { conversations.ensureRunRecovery() } returns Unit
        coEvery { conversations.getConversation("execution") } returns null
        coEvery { conversations.upsertConversation(any()) } returns Unit
        coEvery {
            engine.runOnce("execution", stored.prompt, stored.modelId, "", true, any())
        } returns TaskExecutionEngine.Result.Busy()
        val manager = TaskManager(repository, conversations, engine, backgroundScope)

        val result = manager.executeById(stored.id, "execution")

        val deferred = result as TaskManager.ExecutionResult.Deferred
        assertEquals("execution", deferred.conversationId)
        assertEquals("Conversation is already generating", deferred.reason)
    }

    @Test
    fun executionSummariesJoinConversationRowsToLastPreviews() = runTest {
        val repository = mockk<TaskRepository>()
        val conversations = mockk<ConversationRepository>()
        every { repository.getAllTasks() } returns MutableStateFlow(emptyList())
        val execution = ChatConversation(id = "exec", title = "Run", taskId = "task")
        every { conversations.getExecutionsForTask("task") } returns
            MutableStateFlow(listOf(execution))
        every { conversations.observeExecutionPreviewsForTask("task") } returns MutableStateFlow(
            listOf(
                ExecutionMessagePreview(
                    conversationId = "exec",
                    text = "done",
                    status = MessageStatus.SUCCESS,
                    timestamp = 9L,
                ),
                ExecutionMessagePreview(
                    conversationId = "other",
                    text = "ignored",
                    status = MessageStatus.ERROR,
                    timestamp = 1L,
                ),
            ),
        )
        val manager = TaskManager(repository, conversations, mockk(), backgroundScope)

        val summaries = manager.executionSummariesForTask("task").first()

        assertEquals(1, summaries.size)
        assertEquals("done", summaries.single().preview)
        assertEquals(MessageStatus.SUCCESS, summaries.single().status)
        assertEquals(9L, summaries.single().timestamp)
    }

    private fun task(
        name: String = "Task",
        prompt: String = "Prompt",
    ) = TaskEntity(
        id = "task",
        name = name,
        prompt = prompt,
        cronExpr = "* * * * *",
        nextRunAt = 123L,
        enabled = true,
    )
}
