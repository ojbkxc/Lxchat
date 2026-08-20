package com.lxseek.chat.di

import android.app.Application
import android.content.Context
import com.lxseek.chat.data.MemoryManager
import com.lxseek.chat.data.SettingsManager
import com.lxseek.chat.data.local.ChatDao
import com.lxseek.chat.data.local.ChatDatabase
import com.lxseek.chat.data.repository.ConversationRepository
import com.lxseek.chat.data.repository.SettingsRepository
import com.lxseek.chat.data.repository.TaskRepository
import com.lxseek.chat.data.AutoBackupManager
import com.lxseek.chat.api.local.LocalProvider
import com.lxseek.chat.automation.AutomationScheduler
import com.lxseek.chat.automation.AutomationExecutionGate
import com.lxseek.chat.automation.ConversationExecutionCoordinator
import com.lxseek.chat.automation.LoopManager
import com.lxseek.chat.automation.TaskExecutionEngine
import com.lxseek.chat.automation.TaskManager
import com.lxseek.chat.tool.AutomationToolProvider
import com.lxseek.chat.tool.McpToolProvider
import com.lxseek.chat.mcp.McpRegistry
import com.lxseek.chat.sandbox.SandboxManagerFactory
import com.lxseek.chat.service.TaskWorker
import com.lxseek.chat.viewmodel.ChatViewModel
import com.lxseek.chat.viewmodel.ChatViewModelFactory
import com.lxseek.chat.viewmodel.ConversationStateRegistry
import com.lxseek.chat.viewmodel.ProviderRegistry
import com.lxseek.chat.viewmodel.ShellConfirmationController
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Centralized dependency container (manual DI).
 *
 * Replaces the ad-hoc dependency creation previously spread across
 * MainActivity (ChatDatabase.build, ChatViewModelFactory instantiation).
 * All shared dependencies are created once and reused.
 *
 * This is a stepping stone toward a full DI framework (Hilt/Koin);
 * for a single-module project it provides sufficient decoupling and
 * testability without annotation processing overhead.
 */
class AppContainer(private val appContext: Context) {
    private val application = appContext.applicationContext as Application

    /** App-lifetime scope that backs the shared settings StateFlows.
     *  The handler is the last line of defense: children launched directly on this scope
     *  (settings sync, scheduler, task runners) have no other parent to report to, and an
     *  uncaught exception here would otherwise kill the whole process. */
    private val appScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default +
            kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
                com.lxseek.chat.util.DebugLog.e("AppContainer", "Uncaught in appScope", e)
            }
    )

    // ── Data Layer ────────────────────────────────────────────

    val settingsManager: SettingsManager by lazy { SettingsManager(appContext) }
    val memoryManager: MemoryManager by lazy { MemoryManager(appContext) }
    val database: ChatDatabase by lazy { ChatDatabase.build(appContext) }
    val chatDao: ChatDao by lazy { database.chatDao() }

    // ── Repositories ──────────────────────────────────────────

    val conversationRepository: ConversationRepository by lazy {
        ConversationRepository(chatDao)
    }

    /**
     * Starts process services behind the durable Run-recovery barrier. Scheduling before recovery
     * lets an overdue Worker race the orphan cleanup and inspect an impossible half-live graph.
     */
    fun startProcessServices() {
        appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            conversationRepository.ensureRunRecovery()
            automationScheduler.start()
        }
        // Auto-download Chinese Vosk model for ASR on first launch.
        appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val vosk = com.lxseek.chat.speech.VoskTranscriber(appContext)
                if ("zh" !in vosk.getDownloadedLanguages()) {
                    com.lxseek.chat.util.DebugLog.d("AppContainer", "Auto-downloading zh Vosk model")
                    vosk.downloadModel("zh").collect { /* swallow progress states */ }
                    com.lxseek.chat.util.DebugLog.d("AppContainer", "zh Vosk model auto-download finished")
                } else {
                    com.lxseek.chat.util.DebugLog.d("AppContainer", "zh Vosk model already downloaded, skip")
                }
            } catch (e: Throwable) {
                com.lxseek.chat.util.DebugLog.e("AppContainer", "zh Vosk model auto-download failed", e)
            }
        }
    }

    val taskRepository: TaskRepository by lazy {
        TaskRepository(chatDao)
    }
    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(settingsManager, appScope)
    }

    /** One process-wide confirmation queue shared by Chat, Task, and Loop generation. */
    val shellConfirmationController: ShellConfirmationController by lazy {
        ShellConfirmationController(settingsRepository)
    }

    // ── Generation singletons (process-scoped) ────────────────
    // Shared by both the foreground ChatViewModel and background task execution.
    // [localProvider] must be unique per process (owns the on-device llama engine +
    // LlamaEngine.modelMutex); [providerRegistry] holds the live provider map the
    // generation pipeline reads and runs the long-lived credential/model sync jobs.

    val localProvider: LocalProvider by lazy { LocalProvider(appContext, settingsRepository) }

    val providerRegistry: ProviderRegistry by lazy {
        ProviderRegistry(settingsRepository, localProvider, appScope).also { it.launchSyncJobs() }
    }

    /** Serializes every foreground/background generation touching the same conversation. */
    val conversationExecutionCoordinator: ConversationExecutionCoordinator by lazy {
        ConversationExecutionCoordinator()
    }

    /** Foreground generation slots survive Activity/ViewModel recreation within this process. */
    val conversationStateRegistry: ConversationStateRegistry by lazy {
        ConversationStateRegistry()
    }

    val mcpRegistry: McpRegistry by lazy {
        McpRegistry(appContext, settingsRepository, appScope)
    }

    val mcpToolProvider: McpToolProvider by lazy {
        McpToolProvider(mcpRegistry)
    }

    /** Lets native import quiesce Task/Loop generation without serializing ordinary executions. */
    val automationExecutionGate: AutomationExecutionGate by lazy { AutomationExecutionGate() }

    // ── Sandbox (flavor-specific) ─────────────────────────────

    val sandboxManagerFactory: SandboxManagerFactory? by lazy {
        try {
            // fdroid flavor provides FdroidSandboxManagerFactory
            Class.forName("com.lxseek.chat.sandbox.FdroidSandboxManagerFactory")
                .getDeclaredConstructor(
                    android.content.Context::class.java,
                    com.lxseek.chat.data.repository.SettingsRepository::class.java,
                )
                .newInstance(appContext, settingsRepository) as SandboxManagerFactory
        } catch (_: ClassNotFoundException) {
            // play flavor provides PlaySandboxManagerFactory
            try {
                Class.forName("com.lxseek.chat.sandbox.PlaySandboxManagerFactory")
                    .getDeclaredConstructor()
                    .newInstance() as SandboxManagerFactory
            } catch (_: ClassNotFoundException) {
                null
            } catch (e: Exception) {
                // Class exists but failed to construct — this is a real error, not a flavor miss.
                com.lxseek.chat.util.DebugLog.e("AppContainer", "PlaySandboxManagerFactory init failed", e)
                null
            }
        } catch (e: Exception) {
            // FdroidSandboxManagerFactory exists but failed to construct.
            com.lxseek.chat.util.DebugLog.e("AppContainer", "FdroidSandboxManagerFactory init failed", e)
            null
        }
    }

    // ── Headless task execution (process-scoped) ──────────────
    // Drives a full generation with no ViewModel/UI, reusing the shared generation
    // singletons above. Background Task/Loop runners call its runOnce(...).

    val taskExecutionEngine: TaskExecutionEngine by lazy {
        TaskExecutionEngine(
            application = application,
            appContext = appContext,
            convRepo = conversationRepository,
            settings = settingsRepository,
            memoryManager = memoryManager,
            providerRegistry = providerRegistry,
            localProvider = localProvider,
            sandboxFactory = sandboxManagerFactory,
            appScope = appScope,
            executionCoordinator = conversationExecutionCoordinator,
            shellConfirmation = shellConfirmationController,
            automationExecutionGate = automationExecutionGate,
            mcpToolProvider = mcpToolProvider,
            generationRegistry = conversationStateRegistry,
            pauseConversationLoop = { conversationId -> loopManager.stopLoop(conversationId) },
        )
    }

    val taskManager: TaskManager by lazy {
        TaskManager(
            taskRepository = taskRepository,
            conversationRepository = conversationRepository,
            engine = taskExecutionEngine,
            scope = appScope,
            cancelScheduledExecution = { taskId ->
                TaskWorker.cancel(appContext, taskId)
                automationScheduler.cancelTask(taskId)
            },
            cancelConversationLoop = { conversationId ->
                loopManager.stopLoop(conversationId)
            },
            refreshScheduling = { automationScheduler.refresh() },
            conversationExecutionCoordinator = conversationExecutionCoordinator,
            titleExecutionConversation = taskExecutionEngine::updateTaskExecutionTitle,
        )
    }

    val loopManager: LoopManager by lazy {
        LoopManager(
            taskRepository = taskRepository,
            conversationRepository = conversationRepository,
            engine = taskExecutionEngine,
            cancelWork = { conversationId ->
                com.lxseek.chat.service.LoopWorker.cancel(appContext, conversationId)
            },
            cancelAlarm = { conversationId -> automationScheduler.cancelLoop(conversationId) },
            executionCoordinator = conversationExecutionCoordinator,
            executionGate = automationExecutionGate,
        )
    }

    /** Foreground-only provider: headless automation cannot recursively create automation. */
    val automationToolProvider: AutomationToolProvider by lazy {
        AutomationToolProvider(taskManager, loopManager) {
            settingsManager.automationToolsEnabled.first()
        }
    }

    val automationScheduler: AutomationScheduler by lazy {
        AutomationScheduler(appContext, taskRepository, settingsRepository, appScope).also { it.start() }
    }

    // ── Auto Backup ───────────────────────────────────────────

    val autoBackupManager: AutoBackupManager by lazy {
        AutoBackupManager(appContext, settingsManager, chatDao, memoryManager)
    }

    // ── ViewModel Factory ─────────────────────────────────────

    fun chatViewModelFactory(): ChatViewModelFactory =
        ChatViewModelFactory(
            application, database, chatDao, settingsManager, memoryManager, appContext, sandboxManagerFactory,
            autoBackupManager, conversationRepository, settingsRepository, localProvider, providerRegistry,
            taskManager, loopManager, automationToolProvider, conversationExecutionCoordinator,
            automationExecutionGate, conversationStateRegistry, shellConfirmationController,
            mcpRegistry, mcpToolProvider, taskExecutionEngine,
        )
}
