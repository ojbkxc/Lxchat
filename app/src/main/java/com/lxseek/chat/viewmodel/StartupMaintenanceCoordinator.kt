package com.lxseek.chat.viewmodel

import com.lxseek.chat.data.repository.ConversationRepository
import com.lxseek.chat.data.repository.SettingsRepository
import com.lxseek.chat.util.SnackbarEvent
import com.lxseek.chat.util.UpdateInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Launches independent, one-shot maintenance work when the chat facade starts. */
internal class StartupMaintenanceCoordinator(
    private val settings: SettingsRepository,
    private val conversations: ConversationRepository,
    private val scope: CoroutineScope,
    private val currentVersion: () -> String,
    private val checkUpdate: suspend (String) -> UpdateInfo?,
    private val onUpdateFound: (UpdateInfo) -> Unit,
    private val isCaching: (String) -> Boolean,
    private val cacheMessages: (String) -> Unit,
    private val cacheReminder: (notCached: Int, total: Int, action: () -> Unit) -> SnackbarEvent,
    private val emitSnackbar: suspend (SnackbarEvent) -> Unit,
    private val sweepAttachments: suspend () -> Unit,
    private val onAttachmentSweepFailure: (Exception) -> Unit,
    private val startAutoBackup: () -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    fun start() {
        scope.launch(ioDispatcher) { checkForUpdateIfDue() }
        scope.launch(ioDispatcher) { remindAboutUncachedMessages() }
        scope.launch(ioDispatcher) { conversations.deleteOrphanedEmbeddings() }
        scope.launch(ioDispatcher) {
            try {
                sweepAttachments()
            } catch (error: Exception) {
                onAttachmentSweepFailure(error)
            }
        }
        startAutoBackup()
    }

    private suspend fun checkForUpdateIfDue() {
        if (!settings.getAutoUpdateCheck()) return
        val checkedAt = now()
        if (checkedAt - settings.getLastUpdateCheckTime() <= UPDATE_INTERVAL_MS) return
        settings.saveLastUpdateCheckTime(checkedAt)
        checkUpdate(currentVersion())?.let(onUpdateFound)
    }

    private suspend fun remindAboutUncachedMessages() {
        val activeId = settings.getActiveEmbeddingModelId()
        val active = settings.getEmbeddingModels().find { it.id == activeId } ?: return
        val total = conversations.getIndexableMessageCount()
        val notCached = (total - conversations.getEmbeddingCountByModel(active.id)).coerceAtLeast(0)
        if (notCached == 0 || isCaching(active.id)) return
        emitSnackbar(cacheReminder(notCached, total) { cacheMessages(active.id) })
    }

    private companion object {
        const val UPDATE_INTERVAL_MS = 24 * 60 * 60 * 1000L
    }
}
