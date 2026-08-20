package com.lxseek.chat.automation

import com.lxseek.chat.data.local.TaskEntity
import com.lxseek.chat.data.repository.ConversationRepository
import com.lxseek.chat.data.repository.TaskRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
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
