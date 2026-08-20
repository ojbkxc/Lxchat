package com.lxseek.chat.viewmodel

import com.lxseek.chat.data.EmbeddingModelConfig
import com.lxseek.chat.data.EmbeddingModelType
import com.lxseek.chat.data.repository.ConversationRepository
import com.lxseek.chat.data.repository.SettingsRepository
import com.lxseek.chat.util.SnackbarEvent
import com.lxseek.chat.util.UpdateInfo
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StartupMaintenanceCoordinatorTest {
    @Test
    fun updateCheckAtExactDailyBoundaryIsNotDue() = runTest {
        val fixture = Fixture(this, now = DAY_MS + 1L)
        coEvery { fixture.settings.getAutoUpdateCheck() } returns true
        coEvery { fixture.settings.getLastUpdateCheckTime() } returns 1L

        fixture.coordinator.start()
        runCurrent()

        coVerify(exactly = 0) { fixture.settings.saveLastUpdateCheckTime(any()) }
        assertTrue(fixture.checkedVersions.isEmpty())
    }

    @Test
    fun dueUpdatePersistsTimestampBeforeNetworkAndPublishesResult() = runTest {
        val fixture = Fixture(this, now = DAY_MS + 2L)
        val update = UpdateInfo("3.0", "https://example.invalid", "notes")
        coEvery { fixture.settings.getAutoUpdateCheck() } returns true
        coEvery { fixture.settings.getLastUpdateCheckTime() } returns 1L
        coEvery { fixture.settings.saveLastUpdateCheckTime(DAY_MS + 2L) } answers {
            fixture.events += "timestamp"
        }
        fixture.updateResult = update

        fixture.coordinator.start()
        runCurrent()

        assertEquals(listOf("timestamp", "check"), fixture.events.filter { it != "backup" })
        assertEquals(listOf("current"), fixture.checkedVersions)
        assertEquals(listOf(update), fixture.updates)
    }

    @Test
    fun uncachedReminderKeepsCountsAndExactCacheAction() = runTest {
        val fixture = Fixture(this)
        val active = EmbeddingModelConfig(
            id = "embedding",
            name = "Embedding",
            type = EmbeddingModelType.REMOTE,
        )
        coEvery { fixture.settings.getEmbeddingModels() } returns listOf(active)
        coEvery { fixture.settings.getActiveEmbeddingModelId() } returns active.id
        coEvery { fixture.conversations.getIndexableMessageCount() } returns 10
        coEvery { fixture.conversations.getEmbeddingCountByModel(active.id) } returns 3

        fixture.coordinator.start()
        runCurrent()
        fixture.snackbars.single().onAction?.invoke()

        assertEquals("7/10", fixture.snackbars.single().message)
        assertEquals(listOf(active.id), fixture.cacheRequests)
    }

    @Test
    fun activeCachingSuppressesReminder() = runTest {
        val fixture = Fixture(this, cachingIds = setOf("embedding"))
        val active = EmbeddingModelConfig(
            id = "embedding",
            name = "Embedding",
            type = EmbeddingModelType.LOCAL,
        )
        coEvery { fixture.settings.getEmbeddingModels() } returns listOf(active)
        coEvery { fixture.settings.getActiveEmbeddingModelId() } returns active.id
        coEvery { fixture.conversations.getIndexableMessageCount() } returns 10
        coEvery { fixture.conversations.getEmbeddingCountByModel(active.id) } returns 0

        fixture.coordinator.start()
        runCurrent()

        assertTrue(fixture.snackbars.isEmpty())
    }

    @Test
    fun sweepFailureIsReportedWhileOtherMaintenanceStillRuns() = runTest {
        val error = IllegalStateException("sweep")
        val fixture = Fixture(this, sweepFailure = error)

        fixture.coordinator.start()
        assertEquals(listOf("backup"), fixture.events)
        runCurrent()

        assertSame(error, fixture.sweepFailures.single())
        coVerify(exactly = 1) { fixture.conversations.deleteOrphanedEmbeddings() }
    }

    private class Fixture(
        testScope: kotlinx.coroutines.test.TestScope,
        now: Long = 0L,
        cachingIds: Set<String> = emptySet(),
        private val sweepFailure: Exception? = null,
    ) {
        val settings = mockk<SettingsRepository>()
        val conversations = mockk<ConversationRepository>()
        val events = mutableListOf<String>()
        val checkedVersions = mutableListOf<String>()
        val updates = mutableListOf<UpdateInfo>()
        val snackbars = mutableListOf<SnackbarEvent>()
        val cacheRequests = mutableListOf<String>()
        val sweepFailures = mutableListOf<Exception>()
        var updateResult: UpdateInfo? = null
        private val dispatcher = StandardTestDispatcher(testScope.testScheduler)
        val coordinator = StartupMaintenanceCoordinator(
            settings = settings,
            conversations = conversations,
            scope = testScope,
            currentVersion = { "current" },
            checkUpdate = { version ->
                events += "check"
                checkedVersions += version
                updateResult
            },
            onUpdateFound = updates::add,
            isCaching = { it in cachingIds },
            cacheMessages = cacheRequests::add,
            cacheReminder = { notCached, total, action ->
                SnackbarEvent("$notCached/$total", "cache", action)
            },
            emitSnackbar = snackbars::add,
            sweepAttachments = { sweepFailure?.let { throw it } },
            onAttachmentSweepFailure = sweepFailures::add,
            startAutoBackup = { events += "backup" },
            now = { now },
            ioDispatcher = dispatcher,
        )

        init {
            coEvery { settings.getAutoUpdateCheck() } returns false
            coEvery { settings.getEmbeddingModels() } returns emptyList()
            coEvery { settings.getActiveEmbeddingModelId() } returns ""
            coEvery { conversations.deleteOrphanedEmbeddings() } returns Unit
        }
    }

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
