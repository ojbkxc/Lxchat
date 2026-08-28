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
import com.lxseek.chat.automation.WorkflowManager
import com.lxseek.chat.grok.GrokXOAuthManager
import com.lxseek.chat.tool.AutomationToolProvider
import com.lxseek.chat.tool.AndroidAppControllerToolProvider
import com.lxseek.chat.tool.McpToolProvider
import com.lxseek.chat.mcp.McpRegistry
import com.lxseek.chat.plugin.McpPlugin
import com.lxseek.chat.plugin.BuiltinSkillsPlugin
import com.lxseek.chat.plugin.NativeToolsPlugin
import com.lxseek.chat.plugin.PluginContext
import com.lxseek.chat.plugin.PluginHost
import com.lxseek.chat.sandbox.SandboxManagerFactory
import com.lxseek.chat.service.TaskWorker
import com.lxseek.chat.viewmodel.ChatViewModel
import com.lxseek.chat.viewmodel.ChatViewModelFactory
import com.lxseek.chat.viewmodel.ConversationStateRegistry
import com.lxseek.chat.viewmodel.ProviderRegistry
import com.lxseek.chat.viewmodel.ShellConfirmationController
import com.lxseek.chat.viewmodel.WorkflowViewModel
import com.lxseek.chat.api.router.ApiKeyRotator
import com.lxseek.chat.api.router.ApiKeySource
import com.lxseek.chat.api.router.FallbackChain
import com.lxseek.chat.api.router.RouterConfig
import com.lxseek.chat.api.router.SmartModelRouter
import com.lxseek.chat.api.router.SmartModelRouterFactory
import com.lxseek.chat.data.ActivityJournal
import com.lxseek.chat.skill.SkillHost
import com.lxseek.chat.skill.UserSkillStore
import com.lxseek.chat.tool.JourneyToolProvider
import com.lxseek.chat.tool.QualityToolProvider
import com.lxseek.chat.tool.SkillLearnToolProvider
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
                // 诊断：完整输出的异常 message（如 IllegalAccessError 的 "tried to access method X"）
                // 会被 DebugLog.safeThrowableSummary 过滤掉，这里用平台 Log 保留它以精确定位 R8 访问错误。
                android.util.Log.e("AppContainer", "Uncaught in appScope (full)", e)
            }
    )

    // ── Data Layer ────────────────────────────────────────────

    val settingsManager: SettingsManager by lazy { SettingsManager(appContext) }
    val memoryManager: MemoryManager by lazy { MemoryManager(appContext) }
    val database: ChatDatabase by lazy { ChatDatabase.build(appContext) }
    val chatDao: ChatDao by lazy { database.chatDao() }

    // ── Hermes 式成长系统（journey / 技能学习 / 质量自检）────────────

    /** 活动日志（journey 数据底座）：记录记忆/技能/任务/校验等成长事件。 */
    val activityJournal: ActivityJournal by lazy { ActivityJournal(appContext) }

    /** 用户自建技能持久化（filesDir/skills_user/），由 create_skill 写入、启动时加载。 */
    val userSkillStore: UserSkillStore by lazy { UserSkillStore(appContext) }

    // ── Repositories ──────────────────────────────────────────

    val conversationRepository: ConversationRepository by lazy {
        ConversationRepository(chatDao)
    }

    /**
     * Starts process services behind the durable Run-recovery barrier. Scheduling before recovery
     * lets an overdue Worker race the orphan cleanup and inspect an impossible half-live graph.
     *
     * Every launch is individually guarded: a failure in one background service (recovery,
     * scheduler, IM, proactive messaging) must not cascade-cancel the others.
     */
    fun startProcessServices() {
        appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                conversationRepository.ensureRunRecovery()
            } catch (e: Throwable) {
                com.lxseek.chat.util.DebugLog.e("AppContainer", "ensureRunRecovery failed", e)
            }
            try {
                automationScheduler.start()
            } catch (e: Throwable) {
                com.lxseek.chat.util.DebugLog.e("AppContainer", "automationScheduler.start failed", e)
            }
        }
        // IM automatic reply loop: a self-healing receiver that polls once IM is enabled.
        appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                imPollingReceiver.start()
            } catch (e: Throwable) {
                com.lxseek.chat.util.DebugLog.e("AppContainer", "imPollingReceiver.start failed", e)
            }
        }
        // Proactive messaging: self-healing loop, inert while disabled.
        appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                proactiveMessagingService.start()
            } catch (e: Throwable) {
                com.lxseek.chat.util.DebugLog.e("AppContainer", "proactiveMessagingService.start failed", e)
            }
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
        // Desktop pet: restore the floating bubble at launch when the user enabled it.
        appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                // .first() waits for DataStore's first emission, so a cold start never races the
                // persisted preference.
                com.lxseek.chat.pet.PetEmotionController.enabled = settingsManager.petEmotionEnabled.first()
                if (settingsManager.petOverlayEnabled.first()) {
                    com.lxseek.chat.pet.PetOverlayWindowService.start(appContext)
                }
            } catch (e: Throwable) {
                com.lxseek.chat.util.DebugLog.e("AppContainer", "Desktop pet startup restore failed", e)
            }
        }
        // Condition trigger: dynamically register battery/network receivers. Android O+ no longer
        // delivers implicit broadcasts (ACTION_BATTERY_CHANGED / CONNECTIVITY_CHANGE) to manifest
        // receivers, so we register them here to keep the trigger system actually working. The
        // receivers are inert while the trigger master toggle is off (they read the DataStore and
        // bail out). Process-scoped — never unregistered.
        try {
            com.lxseek.chat.trigger.BatteryTriggerReceiver.registerDynamic(appContext)
            com.lxseek.chat.trigger.NetworkTriggerReceiver.registerDynamic(appContext)
        } catch (e: Throwable) {
            com.lxseek.chat.util.DebugLog.e("AppContainer", "trigger receivers register failed", e)
        }
        // Cron scheduled tasks: scan all enabled CronTasks on startup and arm WorkManager chains.
        // The scheduler self-heals on every tasks Flow emission (add/edit/delete/toggle), so this
        // call only needs to happen once per process. Inert when no tasks are configured.
        appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                cronScheduler.start()
            } catch (e: Throwable) {
                com.lxseek.chat.util.DebugLog.e("AppContainer", "cronScheduler.start failed", e)
            }
        }
    }

    val taskRepository: TaskRepository by lazy {
        TaskRepository(chatDao)
    }

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(settingsManager, appScope, imGatewayStore)
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

    /** Process-scoped token usage tracker for cost analysis and optimization. */
    val tokenUsageTracker: com.lxseek.chat.metrics.TokenUsageTracker by lazy {
        com.lxseek.chat.metrics.TokenUsageTracker()
    }

    val localProvider: LocalProvider by lazy { LocalProvider(appContext, settingsRepository) }

    val providerRegistry: ProviderRegistry by lazy {
        ProviderRegistry(settingsRepository, localProvider, appScope).also {
            try {
                it.launchSyncJobs()
            } catch (e: Throwable) {
                com.lxseek.chat.util.DebugLog.e("AppContainer", "providerRegistry.launchSyncJobs failed", e)
            }
        }
    }

    /** Grok(x.ai) 官方账号登录。登录产出的 access token 写入 [Constants.PROVIDER_GROK] 的活动 API Key。 */
    val grokXOAuthManager: GrokXOAuthManager by lazy {
        GrokXOAuthManager(appContext, settingsRepository, appScope)
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

    // ── Plugin Host ─────────────────────────────────────────
    // 统一插件生态入口：既有 MCP 能力与原生工具集都被包装为 Plugin 注册进来。
    // 生成管线消费 pluginHost.toolProviders() 聚合结果，后续会员门禁在此出口按
    // manifest.requiresMembership 过滤即可，不侵入任何现有功能。

    val pluginContext: PluginContext by lazy {
        PluginContext(appContext, appScope, settingsRepository)
    }

    /** 共享技能注册表：插件技能、内置技能与用户自建技能（UserSkillStore）共用同一实例，
     *  避免 pluginHost 与 skillLearnToolProvider 之间的循环 lazy 依赖。 */
    val skillHost: SkillHost by lazy { SkillHost() }

    val pluginHost: PluginHost by lazy {
        PluginHost(pluginContext, externalSkillHost = skillHost).also { host ->
            host.register(McpPlugin(mcpToolProvider))
            host.register(
                NativeToolsPlugin(
                    listOfNotNull(
                        automationToolProvider,
                        androidControlToolProvider,
                        gitToolProvider,
                        imToolProvider,
                        reminderToolProvider,
                        subAgentToolProvider,
                        deviceToolProvider,
                        runtimeToolProvider,
                        // Hermes 式成长系统：技能学习/Curator、journey、交付前自检。
                        skillLearnToolProvider,
                        journeyToolProvider,
                        qualityToolProvider,
                    ),
                ),
            )
            // Built-in skill templates: validate the plugin → skillHost → disclosure
            // wiring end-to-end. Skills land in host.skillHost on register.
            host.register(BuiltinSkillsPlugin())
            // 用户自建技能（create_skill 沉淀）在启动时注册进 skillHost，随应用恢复。
            runCatching {
                userSkillStore.loadAll().forEach { skill ->
                    skillHost.register(skill, enabled = true)
                }
            }
        }
    }

    /** 技能学习/维护：create/update/delete_skill + list/curate_skills（写操作需审批）。 */
    val skillLearnToolProvider: SkillLearnToolProvider by lazy {
        SkillLearnToolProvider(userSkillStore, skillHost, activityJournal)
    }

    /** 成长旅程：只读聚合活动日志，供模型了解自己的成长轨迹。 */
    val journeyToolProvider: JourneyToolProvider by lazy {
        JourneyToolProvider(activityJournal)
    }

    /** 交付前自检：verify_output。 */
    val qualityToolProvider: QualityToolProvider by lazy {
        QualityToolProvider(activityJournal)
    }

    /** 运行时引擎编排器：下载/解压/启停/版本约束/进程空闲回收。 */
    val runtimeEngineManager: com.lxseek.chat.runtime.RuntimeEngineManager by lazy {
        com.lxseek.chat.runtime.RuntimeEngineManager(appContext, settingsRepository, appScope)
    }

    /** 运行时引擎的 AI 工具集（market_install / runtime_start / novel_inkos 等），常驻披露。 */
    val runtimeToolProvider: com.lxseek.chat.runtime.RuntimeToolProvider by lazy {
        runtimeEngineManager.toolProvider
    }

    /** 插件市场服务：抓取目录、安装/卸载/启停市场插件，启动时离线恢复已装插件。 */
    val pluginMarket: com.lxseek.chat.plugin.market.PluginMarket by lazy {
        com.lxseek.chat.plugin.market.PluginMarket(
            appContext,
            settingsRepository,
            pluginHost,
            appScope,
            runtimeEngineManager,
        )
    }

    /** Read-only Git tools (status/log/diff/branches/remote) executed in the local sandbox. */
    val gitToolProvider: com.lxseek.chat.tool.GitToolProvider by lazy {
        com.lxseek.chat.tool.GitToolProvider(sandboxManagerFactory)
    }

    /** Text-model device control: read the current app's UI tree and click/type in it. */
    val androidControlToolProvider: AndroidAppControllerToolProvider by lazy {
        AndroidAppControllerToolProvider(application)
    }

    /** On-device status/control tools (battery, clipboard, sensors, apps, volume, etc.). */
    val deviceToolProvider: com.lxseek.chat.tool.DeviceToolProvider by lazy {
        com.lxseek.chat.tool.DeviceToolProvider(application)
    }

    /** IM gateway bridge: watches persisted config and exposes the active [com.lxseek.chat.im.MessageChannel]. */
    val imGatewayStore: com.lxseek.chat.im.ImGatewayStore by lazy {
        com.lxseek.chat.im.ImGatewayStore(appContext)
    }

    val imBridgeService: com.lxseek.chat.im.ImBridgeService by lazy {
        com.lxseek.chat.im.ImBridgeService(
            multiConfig = imGatewayStore.multiConfig,
            legacyConfig = imGatewayStore.config,
            scope = appScope,
            cacheDir = appContext.cacheDir,
        )
    }

    /** Closes the IM loop: polls inbound messages, triggers the agent, writes replies back. */
    val proactiveMessagingService: com.lxseek.chat.im.ProactiveMessagingService by lazy {
        com.lxseek.chat.im.ProactiveMessagingService(
            bridge = imBridgeService,
            store = imGatewayStore,
            conversationRepository = conversationRepository,
            taskEngine = taskExecutionEngine,
            scope = appScope,
        )
    }

    /**
     * 系统通知自动回复配置存储。进程级单例，供 ImPollingReceiver 自动写入
     * 「昵称 → ContactMapping」映射，也供 NotificationAutoReplyService 读取。
     */
    val notificationReplyStore: com.lxseek.chat.notification.NotificationReplyStore by lazy {
        com.lxseek.chat.notification.NotificationReplyStore(appContext)
    }

    /**
     * Cron 定时任务持久化（DataStore）。进程级单例，供 [cronScheduler] / CronWorker / CronSettingsPage 共享。
     * 用 lazy 确保首次访问时才创建 DataStore，避免在测试或无 Cron 功能的构建中初始化。
     */
    val cronTaskStore: com.lxseek.chat.cron.CronTaskStore by lazy {
        com.lxseek.chat.cron.CronTaskStore(appContext)
    }

    /**
     * Cron 调度器：监听 [cronTaskStore].tasks Flow，对 enabled 任务用 WorkManager 链式 OneTimeWorkRequest 调度。
     * 在 [startProcessServices] 中启动；CronWorker 执行完后会调用 [com.lxseek.chat.cron.CronScheduler.reschedule] 排下一次。
     */
    val cronScheduler: com.lxseek.chat.cron.CronScheduler by lazy {
        com.lxseek.chat.cron.CronScheduler(
            appContext = appContext,
            store = cronTaskStore,
            scope = appScope,
        )
    }

    /** Closes the IM loop: polls inbound messages, triggers the agent, writes replies back. */
    val imPollingReceiver: com.lxseek.chat.im.ImPollingReceiver by lazy {
        com.lxseek.chat.im.ImPollingReceiver(
            bridge = imBridgeService,
            taskEngine = taskExecutionEngine,
            conversationRepository = conversationRepository,
            store = imGatewayStore,
            scope = appScope,
            notificationReplyStore = notificationReplyStore,
            onMessageHandled = { conversationId ->
                // A real inbound message means the contact isn't idle; postpone a proactive greeting.
                proactiveMessagingService.markActive(conversationId)
            },
        )
    }

    /** Lets the agent send/receive IM through the active gateway channel. */
    val imToolProvider: com.lxseek.chat.tool.ImToolProvider by lazy {
        com.lxseek.chat.tool.ImToolProvider { imBridgeService.currentChannel() }
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

    /**
     * 智能模型路由器工厂（进程级单例）。
     *
     * 将原始 Provider 包装为 [SmartModelRouter]，启用：
     * - API Key 轮换：从 Settings 读取每个 Provider 的多 Key 列表，round-robin 轮换
     * - Fallback Chain：当前为空链（无 UI 配置入口）；后续可从 Settings 读取备用模型配置
     * - 速率限制/并发限制/白名单：当前为宽松配置；后续可从 Settings 读取
     *
     * 工厂返回的包装器与原始 Provider 实现 [com.lxseek.chat.api.LlmProvider] 同一接口，
     * 调用链其余部分（ProviderPassEffectExecutor / ProviderPassRunner）无需任何修改。
     */
    private val apiKeyRotator: ApiKeyRotator by lazy { ApiKeyRotator() }

    private val apiKeySource: ApiKeySource by lazy {
        ApiKeySource { providerName ->
            // 从 Settings 读取该 Provider 的全部 API Key（已按 provider 字段过滤）
            settingsRepository.apiKeys.value.filter { it.provider == providerName }
        }
    }

    val smartRouterFactory: SmartModelRouterFactory by lazy {
        SmartModelRouterFactory { delegate, providerName, modelId ->
            // 当前默认配置：启用 Key 轮换，Fallback 暂空，白名单宽松
            val routerConfig = RouterConfig(
                enableFallback = false,       // 暂无备用模型配置入口
                enableKeyRotation = true,     // 启用多 Key 轮换
            )
            SmartModelRouter(
                delegate = delegate,
                routerConfig = routerConfig,
                fallbackChain = FallbackChain.EMPTY,
                apiKeyRotator = apiKeyRotator,
                apiKeySource = apiKeySource,
            )
        }
    }

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
            androidControlToolProvider = androidControlToolProvider,
            gitToolProvider = gitToolProvider,
            imToolProvider = imToolProvider,
            reminderToolProvider = reminderToolProvider,
            deviceToolProvider = deviceToolProvider,
            generationRegistry = conversationStateRegistry,
            pauseConversationLoop = { conversationId -> loopManager.stopLoop(conversationId) },
            smartRouterFactory = smartRouterFactory,
            activityJournal = activityJournal,
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

    val workflowManager: WorkflowManager by lazy {
        WorkflowManager(
            taskRepository = taskRepository,
            conversationRepository = conversationRepository,
            engine = taskExecutionEngine,
            scope = appScope,
        )
    }

    /** Subagents reuse the one-shot Task machinery to run async delegated prompts. */
    val subAgentManager: com.lxseek.chat.automation.SubAgentManager by lazy {
        com.lxseek.chat.automation.SubAgentManager(taskManager, appScope)
    }

    /** Foreground-only provider: headless automation cannot recursively create automation. */
    val automationToolProvider: AutomationToolProvider by lazy {
        AutomationToolProvider(taskManager, loopManager, journal = activityJournal) {
            settingsManager.automationToolsEnabled.first()
        }
    }

    /** Turns natural-language reminder requests into persisted background Tasks. */
    val reminderToolProvider: com.lxseek.chat.tool.ReminderToolProvider by lazy {
        com.lxseek.chat.tool.ReminderToolProvider(taskManagerProvider = { taskManager }) {
            settingsManager.automationToolsEnabled.first()
        }
    }

    val subAgentToolProvider: com.lxseek.chat.tool.SubAgentToolProvider by lazy {
        com.lxseek.chat.tool.SubAgentToolProvider(subAgentManager) {
            settingsManager.automationToolsEnabled.first()
        }
    }

    val automationScheduler: AutomationScheduler by lazy {
        AutomationScheduler(appContext, taskRepository, settingsRepository, appScope).also {
            try {
                it.start()
            } catch (e: Throwable) {
                com.lxseek.chat.util.DebugLog.e("AppContainer", "automationScheduler lazy start failed", e)
            }
        }
    }

    // ── Auto Backup ───────────────────────────────────────────

    val autoBackupManager: AutoBackupManager by lazy {
        AutoBackupManager(appContext, settingsManager, chatDao, memoryManager)
    }

    // ── Membership ────────────────────────────────────────────
    // Local offline membership provider (DataStore-backed) and the redemption code validator.
    // The HMAC secret key is a placeholder constant; production builds should inject it via
    // BuildConfig / native layer so it never ships in plain text in the APK.

    val membershipProvider: com.lxseek.chat.membership.LocalMembershipProvider by lazy {
        com.lxseek.chat.membership.LocalMembershipProvider(settingsManager)
    }

    val redemptionCodeValidator: com.lxseek.chat.membership.RedemptionCodeValidator by lazy {
        com.lxseek.chat.membership.RedemptionCodeValidator(
            com.lxseek.chat.membership.RedemptionNativeBridge.getHmacSecret(),
        )
    }

    // ── ViewModel Factory ─────────────────────────────────────

    fun chatViewModelFactory(): ChatViewModelFactory =
        ChatViewModelFactory(
            application, database, chatDao, settingsManager, memoryManager, appContext, sandboxManagerFactory,
            autoBackupManager, conversationRepository, settingsRepository, localProvider, providerRegistry,
            taskManager, loopManager, conversationExecutionCoordinator,
            automationExecutionGate, conversationStateRegistry, shellConfirmationController,
            mcpRegistry, pluginHost, pluginMarket, taskExecutionEngine,
            membershipProvider, redemptionCodeValidator, smartRouterFactory,
            activityJournal,
        )


    /** Factory for the workflow editor's dedicated view-model (kept out of ChatViewModel). */
    fun workflowViewModelFactory(): androidx.lifecycle.ViewModelProvider.Factory =
        WorkflowViewModel.Factory(workflowManager)
}
