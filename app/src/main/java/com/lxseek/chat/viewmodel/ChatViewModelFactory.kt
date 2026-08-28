package com.lxseek.chat.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.lxseek.chat.data.AutoBackupManager
import com.lxseek.chat.data.MemoryManager
import com.lxseek.chat.data.SettingsManager
import com.lxseek.chat.api.local.LocalProvider
import com.lxseek.chat.automation.TaskExecutionEngine
import com.lxseek.chat.automation.TaskManager
import com.lxseek.chat.automation.LoopManager
import com.lxseek.chat.automation.AutomationExecutionGate
import com.lxseek.chat.automation.ConversationExecutionCoordinator
import com.lxseek.chat.mcp.McpRegistry
import com.lxseek.chat.data.local.ChatDao
import com.lxseek.chat.data.local.ChatDatabase
import com.lxseek.chat.data.repository.ConversationRepository
import com.lxseek.chat.data.repository.SettingsRepository
import com.lxseek.chat.plugin.PluginHost
import com.lxseek.chat.sandbox.SandboxManagerFactory

class ChatViewModelFactory(
    private val application: Application,
    private val database: ChatDatabase,
    private val chatDao: ChatDao,
    private val settingsManager: SettingsManager,
    private val memoryManager: MemoryManager,
    private val context: Context,
    private val sandboxFactory: SandboxManagerFactory? = null,
    private val autoBackupManager: AutoBackupManager,
    private val conversationRepository: ConversationRepository,
    private val settingsRepository: SettingsRepository,
    private val localProvider: LocalProvider,
    private val providerRegistry: ProviderRegistry,
    private val taskManager: TaskManager,
    private val loopManager: LoopManager,
    private val conversationExecutionCoordinator: ConversationExecutionCoordinator,
    private val automationExecutionGate: AutomationExecutionGate,
    private val conversationStateRegistry: ConversationStateRegistry,
    private val shellConfirmationController: ShellConfirmationController,
    private val mcpRegistry: McpRegistry,
    private val pluginHost: PluginHost,
    private val pluginMarket: com.lxseek.chat.plugin.market.PluginMarket,
    private val taskExecutionEngine: TaskExecutionEngine,
    private val membershipProvider: com.lxseek.chat.membership.LocalMembershipProvider,
    private val redemptionCodeValidator: com.lxseek.chat.membership.RedemptionCodeValidator,
    private val smartRouterFactory: com.lxseek.chat.api.router.SmartModelRouterFactory? = null,
    private val activityJournal: com.lxseek.chat.data.ActivityJournal? = null,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ChatViewModel(
                application, database, chatDao, settingsManager, memoryManager, context, sandboxFactory,
                autoBackupManager, conversationRepository, settingsRepository, localProvider, providerRegistry,
                taskManager, loopManager, conversationExecutionCoordinator,
                automationExecutionGate, conversationStateRegistry, shellConfirmationController,
                mcpRegistry, pluginHost, pluginMarket, taskExecutionEngine,
                membershipProvider, redemptionCodeValidator, smartRouterFactory,
                activityJournal,
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
