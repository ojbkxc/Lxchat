package com.lxseek.chat.viewmodel

import android.app.Application
import com.lxseek.chat.data.AutoBackupManager
import com.lxseek.chat.data.MemoryManager
import com.lxseek.chat.data.repository.ConversationRepository
import com.lxseek.chat.data.repository.SettingsRepository
import com.lxseek.chat.service.AutoBackupWorker
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal interface AutoBackupSchedulePort {
    fun schedule()
    fun cancel()
}

internal class AndroidAutoBackupSchedulePort(
    private val application: Application,
) : AutoBackupSchedulePort {
    override fun schedule() = AutoBackupWorker.schedule(application)
    override fun cancel() = AutoBackupWorker.cancel(application)
}

/** Owns Data Control counts and the automatic-backup/delete settings lifecycle. */
class DataControlController internal constructor(
    private val conversations: ConversationRepository,
    private val memory: MemoryManager,
    private val settings: SettingsRepository,
    private val backupManager: AutoBackupManager,
    private val backupSchedule: AutoBackupSchedulePort,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _conversationCount = MutableStateFlow(0)
    val conversationCount: StateFlow<Int> = _conversationCount.asStateFlow()

    private val _memoryCount = MutableStateFlow(0)
    val memoryCount: StateFlow<Int> = _memoryCount.asStateFlow()

    private val _systemPromptCount = MutableStateFlow(0)
    val systemPromptCount: StateFlow<Int> = _systemPromptCount.asStateFlow()

    internal fun startAutoBackup() {
        try {
            backupSchedule.schedule()
        } catch (error: Exception) {
            DebugLog.e("ChatViewModel", "AutoBackupWorker.schedule failed", error)
        }
        scope.launch(ioDispatcher) {
            try {
                backupManager.checkAndBackup()
            } catch (error: Exception) {
                DebugLog.e("ChatViewModel", "Auto backup check failed", error)
            }
        }
    }

    internal fun destroy() {
        backupManager.destroy()
    }

    fun refreshCounts() {
        scope.launch(ioDispatcher) {
            _conversationCount.value = conversations.getAllConversationsList().size
            _memoryCount.value = memory.listFiles().size +
                (if (memory.getActiveMemory().isNotEmpty()) 1 else 0)
            _systemPromptCount.value = settings.getSystemPrompts().size
        }
    }

    fun setAutoBackupEnabled(enabled: Boolean) {
        scope.launch(ioDispatcher) {
            settings.saveAutoBackupEnabled(enabled)
            if (enabled) {
                try {
                    backupSchedule.schedule()
                } catch (_: Exception) {
                }
            } else {
                try {
                    backupSchedule.cancel()
                } catch (_: Exception) {
                }
            }
        }
    }

    fun setAutoBackupPeriodHours(hours: Int) {
        scope.launch(ioDispatcher) {
            settings.saveAutoBackupPeriodHours(hours)
            val deleteHours = settings.autoDeletePeriodHours.value
            if (deleteHours <= hours) {
                val nextDelete = AUTO_DELETE_TIERS_HOURS.firstOrNull { it > hours }
                    ?: AUTO_DELETE_TIERS_HOURS.last()
                settings.saveAutoDeletePeriodHours(nextDelete)
            }
        }
    }

    fun setAutoBackupCategories(categories: String) {
        scope.launch(ioDispatcher) { settings.saveAutoBackupCategories(categories) }
    }

    fun setAutoBackupDirectory(path: String) {
        scope.launch(ioDispatcher) { settings.saveAutoBackupDirectory(path) }
    }

    fun setAutoDeleteEnabled(enabled: Boolean) {
        scope.launch(ioDispatcher) { settings.saveAutoDeleteEnabled(enabled) }
    }

    fun setAutoDeletePeriodHours(hours: Int) {
        scope.launch(ioDispatcher) {
            val backupHours = settings.autoBackupPeriodHours.value
            val minimumValid = AUTO_DELETE_TIERS_HOURS.firstOrNull { it > backupHours }
                ?: AUTO_DELETE_TIERS_HOURS.last()
            settings.saveAutoDeletePeriodHours(maxOf(hours, minimumValid))
        }
    }

    private companion object {
        val AUTO_DELETE_TIERS_HOURS = listOf(168, 720, 8760)
    }
}
