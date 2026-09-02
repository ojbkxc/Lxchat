package com.lxseek.chat.viewmodel

import com.lxseek.chat.data.AutoBackupManager
import com.lxseek.chat.data.BackupResult
import com.lxseek.chat.data.MemoryManager
import com.lxseek.chat.data.local.ChatEntity
import com.lxseek.chat.data.repository.ConversationRepository
import com.lxseek.chat.data.SettingsManager
import com.lxseek.chat.data.repository.SettingsRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DataControlControllerTest {
    @Test
    fun `refresh counts conversations files active memory and prompts on owned dispatcher`() =
        runTest {
            val conversations = mockk<ConversationRepository>()
            val memory = mockk<MemoryManager>()
            val settings = settings()
            coEvery { conversations.getAllConversationsList() } returns listOf(
                ChatEntity("one", "One"),
                ChatEntity("two", "Two"),
            )
            every { memory.listFiles() } returns listOf(
                MemoryManager.MemoryFileInfo("memory.md"),
            )
            every { memory.getActiveMemory() } returns "active"
            coEvery { settings.sm.systemPrompts.first() } returns emptyList()
            val controller = controller(conversations, memory, settings)

            controller.refreshCounts()
            runCurrent()

            assertEquals(2, controller.conversationCount.value)
            assertEquals(2, controller.memoryCount.value)
            assertEquals(0, controller.systemPromptCount.value)
        }

    @Test
    fun `backup and delete period commands preserve strict tier relationship and write order`() =
        runTest {
            val settings = settings(
                backupPeriodHours = 24,
                deletePeriodHours = 168,
            )
            val controller = controller(settings = settings)

            controller.setAutoBackupPeriodHours(168)
            runCurrent()
            coVerifyOrder {
                settings.sm.saveAutoBackupPeriodHours(168)
                settings.sm.saveAutoDeletePeriodHours(720)
            }

            controller.setAutoDeletePeriodHours(1)
            runCurrent()
            coVerifyOrder {
                settings.sm.saveAutoDeletePeriodHours(168)
            }
        }

    @Test
    fun `startup and enabled commands use scheduler while destroy releases backup manager`() =
        runTest {
            val settings = settings()
            val backupManager = mockk<AutoBackupManager>()
            val schedule = FakeAutoBackupSchedule()
            coEvery { backupManager.checkAndBackup() } returns BackupResult.NOT_DUE
            every { backupManager.destroy() } just Runs
            val controller = controller(
                settings = settings,
                backupManager = backupManager,
                schedule = schedule,
            )

            controller.startAutoBackup()
            runCurrent()
            controller.setAutoBackupEnabled(true)
            controller.setAutoBackupEnabled(false)
            runCurrent()
            controller.destroy()

            assertEquals(2, schedule.scheduleCount)
            assertEquals(1, schedule.cancelCount)
            coVerifyOrder {
                settings.sm.saveAutoBackupEnabled(true)
                settings.sm.saveAutoBackupEnabled(false)
            }
            verify(exactly = 1) { backupManager.destroy() }
        }

    private fun TestScope.controller(
        conversations: ConversationRepository = mockk(),
        memory: MemoryManager = mockk(),
        settings: SettingsRepository = settings(),
        backupManager: AutoBackupManager = mockk(relaxed = true),
        schedule: AutoBackupSchedulePort = FakeAutoBackupSchedule(),
    ) = DataControlController(
        conversations = conversations,
        memory = memory,
        settings = settings,
        backupManager = backupManager,
        backupSchedule = schedule,
        scope = this,
        ioDispatcher = StandardTestDispatcher(testScheduler),
    )

    private fun settings(
        backupPeriodHours: Int = 24,
        deletePeriodHours: Int = 168,
    ): SettingsRepository = mockk<SettingsRepository>().also { settings ->
        val sm = mockk<SettingsManager>(relaxed = true)
        every { settings.sm } returns sm
        every { settings.autoBackupPeriodHours } returns MutableStateFlow(backupPeriodHours)
        every { settings.autoDeletePeriodHours } returns MutableStateFlow(deletePeriodHours)
        coEvery { settings.sm.saveAutoBackupEnabled(any()) } returns Unit
        coEvery { settings.sm.saveAutoBackupPeriodHours(any()) } returns Unit
        coEvery { settings.sm.saveAutoBackupCategories(any()) } returns Unit
        coEvery { settings.sm.saveAutoBackupDirectory(any()) } returns Unit
        coEvery { settings.sm.saveAutoDeleteEnabled(any()) } returns Unit
        coEvery { settings.sm.saveAutoDeletePeriodHours(any()) } returns Unit
    }

    private class FakeAutoBackupSchedule : AutoBackupSchedulePort {
        var scheduleCount = 0
        var cancelCount = 0

        override fun schedule() {
            scheduleCount++
        }

        override fun cancel() {
            cancelCount++
        }
    }
}
