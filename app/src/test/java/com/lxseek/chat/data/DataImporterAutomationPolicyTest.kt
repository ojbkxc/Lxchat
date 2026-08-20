package com.lxseek.chat.data

import com.lxseek.chat.automation.LoopPolicy
import com.lxseek.chat.data.local.ChatEntity
import com.lxseek.chat.data.local.LoopEntity
import com.lxseek.chat.data.local.TaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DataImporterAutomationPolicyTest {
    @Test
    fun scheduledTask_isRestoredFailClosed() {
        val imported = sanitizeImportedTask(
            task(cron = "  * * * * *  ", nextRunAt = 123L).copy(
                name = "  Task  ",
                prompt = "  Prompt  ",
            )
        )

        assertFalse(imported.enabled)
        assertEquals(0L, imported.nextRunAt)
        assertEquals("Task", imported.name)
        assertEquals("Prompt", imported.prompt)
        assertEquals("* * * * *", imported.cronExpr)
    }

    @Test
    fun blankRequiredTaskFields_remainVisibleButDisabled() {
        val imported = sanitizeImportedTask(
            task(cron = "not a cron", nextRunAt = Long.MAX_VALUE).copy(
                name = "  ",
                prompt = "\n",
            )
        )

        assertFalse(imported.enabled)
        assertEquals(0L, imported.nextRunAt)
        assertEquals("", imported.name)
        assertEquals("", imported.prompt)
    }

    @Test
    fun manualAndDisabledTasks_haveNoScheduledEpoch() {
        val manual = sanitizeImportedTask(task(cron = "  ", nextRunAt = 123L))
        val disabled = sanitizeImportedTask(
            task(cron = "* * * * *", nextRunAt = 456L).copy(enabled = false),
        )

        assertFalse(manual.enabled)
        assertEquals(0L, manual.nextRunAt)
        assertFalse(disabled.enabled)
        assertEquals(0L, disabled.nextRunAt)
    }

    @Test
    fun legacyUnboundedLoop_getsBoundedDefault() {
        val imported = sanitizeImportedLoop(loop(maxCycles = null))

        assertEquals(LoopPolicy.DEFAULT_MAX_CYCLES, imported.maxCycles)
        assertFalse(imported.active)
        assertEquals(0L, imported.nextFireAt)
    }

    @Test
    fun everyImportedLoop_isInactiveAndHasNoArmedEpoch() {
        val valid = sanitizeImportedLoop(loop())
        val badInterval = sanitizeImportedLoop(loop(intervalMs = 1L))
        val badMaximum = sanitizeImportedLoop(loop(maxCycles = Int.MAX_VALUE))
        val exhausted = sanitizeImportedLoop(
            loop(maxCycles = 2, cycleCount = 2),
        )
        val negative = sanitizeImportedLoop(loop(cycleCount = -1))

        assertFalse(valid.active)
        assertEquals(0L, valid.nextFireAt)
        assertFalse(badInterval.active)
        assertFalse(badMaximum.active)
        assertEquals(LoopPolicy.DEFAULT_MAX_CYCLES, badMaximum.maxCycles)
        assertFalse(exhausted.active)
        assertFalse(negative.active)
        assertEquals(0, negative.cycleCount)
    }

    @Test
    fun orphanTaskConversation_isDetachedAndGraduated() {
        val imported = sanitizeImportedConversation(
            conversation = ChatEntity(
                id = "execution",
                title = "Execution",
                taskId = "missing-task",
                origin = "task",
                graduated = false,
            ),
            availableTaskIds = setOf("existing-task", "imported-task"),
        )

        assertNull(imported.taskId)
        assertEquals("user", imported.origin)
        assertTrue(imported.graduated)
    }

    @Test
    fun conversationAttachedToExistingMergeTask_staysAttached() {
        val conversation = ChatEntity(
            id = "execution",
            title = "Execution",
            taskId = "existing-task",
            origin = "task",
            graduated = false,
        )

        val imported = sanitizeImportedConversation(
            conversation = conversation,
            availableTaskIds = setOf("existing-task", "imported-task"),
        )

        assertEquals(conversation, imported)
    }

    @Test
    fun tasksOnlyBackup_hasSelectableConversationGraph() {
        val preview = DataImporter.ImportPreview(
            manifest = DataImporter.ImportManifest(),
            taskCount = 1,
        )

        assertTrue(preview.hasConversationGraph)
    }

    private fun task(cron: String, nextRunAt: Long) = TaskEntity(
        id = "task",
        name = "Task",
        prompt = "Prompt",
        cronExpr = cron,
        nextRunAt = nextRunAt,
    )

    private fun loop(
        intervalMs: Long = LoopPolicy.MIN_INTERVAL_MS,
        cycleCount: Int = 0,
        maxCycles: Int? = LoopPolicy.DEFAULT_MAX_CYCLES,
    ) = LoopEntity(
        conversationId = "conversation",
        intervalMs = intervalMs,
        nextFireAt = 123L,
        cycleCount = cycleCount,
        maxCycles = maxCycles,
    )
}
