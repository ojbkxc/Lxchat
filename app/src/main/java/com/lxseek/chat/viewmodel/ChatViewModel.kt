package com.lxseek.chat.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lxseek.chat.R
import com.lxseek.chat.api.*
import com.lxseek.chat.api.LlamaEngine
import com.lxseek.chat.api.anthropic.*
import com.lxseek.chat.api.gemini.*
import com.lxseek.chat.api.local.*
import com.lxseek.chat.api.ollama.*
import com.lxseek.chat.api.openai.*
import com.lxseek.chat.data.AutoBackupManager
import com.lxseek.chat.data.BuiltInPrompts
import com.lxseek.chat.data.ConversationSettings
import com.lxseek.chat.data.DataExporter
import com.lxseek.chat.data.DataImporter
import com.lxseek.chat.data.MemoryManager
import com.lxseek.chat.data.PredefinedVariables
import com.lxseek.chat.data.ShellDeviceConfig
import com.lxseek.chat.data.local.ChatEntity
import com.lxseek.chat.data.local.GlobalSearchResult
import com.lxseek.chat.data.repository.ConversationRepository
import com.lxseek.chat.data.repository.SettingsRepository
import com.lxseek.chat.model.AttachmentItem
import com.lxseek.chat.model.ChatConversation
import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.MessageStatus
import com.lxseek.chat.model.apiModelName
import com.lxseek.chat.model.SelectedAttachment
import com.lxseek.chat.sandbox.SandboxManager
import com.lxseek.chat.sandbox.SandboxManagerFactory
import com.lxseek.chat.service.LxChatForegroundService
import com.lxseek.chat.tool.ToolApprovalResult
import com.lxseek.chat.util.DebugLog
import com.lxseek.chat.util.NetworkMonitor
import com.lxseek.chat.util.TtsManager
import com.lxseek.chat.util.PdfPageRenderer
import com.lxseek.chat.util.SnackbarEvent
import com.lxseek.chat.util.UpdateChecker
import com.lxseek.chat.util.UpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChatViewModel(
    application: Application,
    // [chatDao] and [settingsManager] are retained ONLY to pass to ImportExportManager,
    // which threads them into DataExporter/DataImporter (bulk data-layer utilities that
    // genuinely need raw DAO/DataStore). All other managers use repositories uniformly.
    private val database: com.lxseek.chat.data.local.ChatDatabase,
    private val chatDao: com.lxseek.chat.data.local.ChatDao,
    private val settingsManager: com.lxseek.chat.data.SettingsManager,
    val memoryManager: MemoryManager,
    private val appContext: Context,
    private val sandboxFactory: SandboxManagerFactory? = null,
    // All injected via AppContainer/ChatViewModelFactory — the single construction site.
    autoBackupManager: AutoBackupManager,
    conversationRepository: ConversationRepository,
    settingsRepository: SettingsRepository,
    // Process-scoped generation singletons, shared with background task execution.
    private val localProvider: LocalProvider,
    private val providerRegistry: ProviderRegistry,
    // App-scoped automation orchestrator (task CRUD + run-now).
    private val taskManager: com.lxseek.chat.automation.TaskManager,
    private val loopManager: com.lxseek.chat.automation.LoopManager,
    private val automationToolProvider: com.lxseek.chat.tool.AutomationToolProvider,
    private val conversationExecutionCoordinator: com.lxseek.chat.automation.ConversationExecutionCoordinator,
    private val automationExecutionGate: com.lxseek.chat.automation.AutomationExecutionGate,
    private val generationRegistry: ConversationStateRegistry,
    private val shellConfirmation: ShellConfirmationController,
    private val mcpRegistry: com.lxseek.chat.mcp.McpRegistry,
    private val mcpToolProvider: com.lxseek.chat.tool.McpToolProvider,
    private val androidControlToolProvider: com.lxseek.chat.tool.AndroidAppControllerToolProvider,
    private val imToolProvider: com.lxseek.chat.tool.ImToolProvider? = null,
    private val reminderToolProvider: com.lxseek.chat.tool.ReminderToolProvider? = null,
    private val taskExecutionEngine: com.lxseek.chat.automation.TaskExecutionEngine,
) : AndroidViewModel(application) {

    val settings: SettingsRepository = settingsRepository

    // Lightweight connectivity monitor used to pre-check before sending and to surface a
    // snackbar when the device is offline. Registered lazily; callers use isOnline() / online.
    private val networkMonitor = NetworkMonitor(appContext)

    /** Synchronous online check backed by ConnectivityManager. */
    fun isOnline(): Boolean = networkMonitor.isOnline()

    /**
     * Conversation/message persistence behind the repository layer. CRUD, cascade-delete,
     * branch-selection and stuck-message logic live in [ConversationRepository]; managers
     * receive the repository (not raw DAO) for a uniform boundary.
     */
    private val convRepo: ConversationRepository = conversationRepository
    private val composerDrafts = ComposerDraftController(conversationRepository)
    val dataControl = DataControlController(
        conversations = conversationRepository,
        memory = memoryManager,
        settings = settingsRepository,
        backupManager = autoBackupManager,
        backupSchedule = AndroidAutoBackupSchedulePort(application),
        scope = viewModelScope,
    )
    private val conversationForkShare =
        ConversationForkShareService(
            conversationRepository,
            settingsRepository,
            File(application.filesDir, "fork-attachments"),
        )
    private val conversationForkShareController by lazy {
        ConversationForkShareController(
            currentConversationId = currentConversationId,
            service = conversationForkShare,
            scope = viewModelScope,
            onConversationForked = selectionController::selectConversation,
            onShareReady = _conversationShareText::emit,
            forkFailureText = { reason ->
                appContext.getString(R.string.conversation_fork_failed, reason)
            },
            shareFailureText = { reason ->
                appContext.getString(R.string.conversation_share_failed, reason)
            },
            onFailure = { message -> _snackbarMessage.emit(SnackbarEvent(message)) },
        )
    }
    private val conversationLifecycleController by lazy {
        ConversationLifecycleController(
            currentConversationId = currentConversationId,
            conversations = convRepo,
            scope = viewModelScope,
            stopLoop = { conversationId -> loopManager.stopLoop(conversationId) },
            withConversationLock = { conversationId, block ->
                conversationExecutionCoordinator.withConversationLock(conversationId) { block() }
            },
            removeRuntime = generationRegistry::remove,
            stopVisibleGeneration = generationStopAdapter::stopVisibleConversation,
            openNewChat = selectionController::createNewChat,
        )
    }

    /** Embedding subsystem: model CRUD + RAG cache + single-message indexing + key resolution. */
    val ragManager = RagManager(
        conversations = convRepo,
        settings = settings,
        localProvider = localProvider,
        appContext = appContext,
        scope = viewModelScope,
    ) { _snackbarMessage.emit(it) }

    /**
     * Data export/import orchestration (native backup + Claude + GPT formats).
     * [chatDao] and [settingsManager] are passed through to [DataExporter]/[DataImporter]
     * which need raw DAO/DataStore for bulk cross-table operations.
     */
    val importExport = ImportExportManager(
        app = getApplication(),
        conversations = convRepo,
        database = database,
        chatDao = chatDao,
        settingsManager = settingsManager,
        memoryManager = memoryManager,
        scope = viewModelScope,
        emitSnackbar = { _snackbarMessage.emit(it) },
        onDataChanged = dataControl::refreshCounts,
        automationExecutionGate = automationExecutionGate,
        quiesceAutomation = {
            taskManager.cancelAllExecutionsForImport()
            loopManager.cancelAllExecutionsForImport()
        },
        resumeAutomationScheduling = taskManager::refreshSchedulingAfterImport,
    )

    /** Local (on-device) chat-model configuration CRUD. */
    val modelManager = ModelManager(settings, viewModelScope)
    private val customModelConfiguration = CustomModelConfigurationController(
        providers = providerRegistry,
        conversations = convRepo,
        settings = settings,
        scope = viewModelScope,
        onModelReferenceReplaced = { oldModelId, newModelId ->
            selectionController.replaceActiveModelReference(oldModelId, newModelId)
        },
    )

    // [providerRegistry] and [localProvider] are now constructor-injected, process-scoped
    // singletons (see AppContainer) so background task execution shares the same instances.

    /**
     * Startup jobs deferred until all StateFlow/property backing fields are
     * initialized — avoids the constructor this-escape where a Dispatchers.IO
     * coroutine accesses a field whose JVM backing field is still null.
     */
    private val proxySettingsSynchronizer = ProxySettingsSynchronizer(
        settings = settings,
        scope = viewModelScope,
        apply = com.lxseek.chat.api.HttpClient::setProxy,
    )
    private val localModelCatalogSynchronizer = LocalModelCatalogSynchronizer(
        settings = settings,
        scope = viewModelScope,
    )
    private val startupMaintenance by lazy {
        val attachmentSweeper = AttachmentOrphanSweeper(convRepo, application.filesDir)
        StartupMaintenanceCoordinator(
            settings = settings,
            conversations = convRepo,
            scope = viewModelScope,
            currentVersion = ::getCurrentVersion,
            checkUpdate = UpdateChecker::check,
            onUpdateFound = { _updateDialogData.value = it },
            isCaching = { ragManager.cachingProgress.value.containsKey(it) },
            cacheMessages = ragManager::cacheMessagesForModel,
            cacheReminder = { notCached, total, action ->
                SnackbarEvent(
                    getApplication<Application>().getString(
                        R.string.messages_not_cached,
                        notCached,
                        total,
                    ),
                    getApplication<Application>().getString(R.string.cache_now),
                    action,
                )
            },
            emitSnackbar = _snackbarMessage::emit,
            sweepAttachments = attachmentSweeper::sweep,
            onAttachmentSweepFailure = { error ->
                DebugLog.d("ChatViewModel", "Attachment orphan sweep error", error)
            },
            startAutoBackup = dataControl::startAutoBackup,
        )
    }

    private fun startInitJobs() {
        proxySettingsSynchronizer.start()
        startupMaintenance.start()
        localModelCatalogSynchronizer.start()
        // Provider map / model-list sync jobs now run on the process-scoped registry
        // (launched once in AppContainer), so they survive ViewModel recreation.
    }

    // Per-conversation generation lifecycle (IO scope, job, slot, race-free stop/persist tokens)
    // lives in [ConversationGenerationState], one per conversation via [generationRegistry].

    private val generationManager by lazy {
        GenerationManager(
            app = application,
            conversations = convRepo,
            memoryManager = memoryManager,
            providers = providerRegistry.all,
            context = appContext,
            sandboxFactory = sandboxFactory,
            additionalToolProviders = listOfNotNull(
                automationToolProvider, mcpToolProvider, androidControlToolProvider, imToolProvider,
                reminderToolProvider,
            ),
        ).also { gm ->
            // Gate lives in RagManager.indexMessageForRag (autoCacheEnabled + active model).
            gm.onMessagePersisted = { messageId, text -> ragManager.indexMessageForRag(messageId, text) }
            gm.onConfirmShellCommand = { server, summary -> shellConfirmation.confirm(server, summary) }
            gm.onToolApproval = { request ->
                val allowed = shellConfirmation.confirm(request.toolName, request.summary)
                if (allowed) ToolApprovalResult.Approved
                else ToolApprovalResult.Denied("user declined")
            }
        }
    }
    private val semanticSearchService by lazy {
        SemanticSearchService(
            settings = settings,
            activeEmbeddingConfig = { ragManager.activeEmbeddingModel.value },
            resolveEmbeddingApiKey = ragManager::resolveEmbeddingApiKey,
            search = generationManager::semanticSearch,
        )
    }

    val sandboxManager: SandboxManager? by lazy {
        sandboxFactory?.create()
    }
    val isSandboxFlavor: Boolean = sandboxFactory?.isAvailable() == true
    val mcpServerSnapshots: StateFlow<Map<String, com.lxseek.chat.mcp.McpServerSnapshot>>
        get() = mcpRegistry.snapshots

    fun refreshMcpServer(serverId: String) = mcpRegistry.refresh(serverId)

    override fun onCleared() {
        super.onCleared()
        // The engine and the registry are process-scoped while this ViewModel is not, so every
        // reference either of them holds must be released here or the whole graph leaks.
        foregroundAutomationBridge.close()
        sandboxManager?.close()
        generationRegistry.detachUiCallbacks(generationCallbackOwner)
        dataControl.destroy()
        voiceConversation.dispose()
        TtsManager.stop()
        networkMonitor.unregister()
    }

    /** Nullable on purpose: the provider settings page recomposes one frame after a custom
     *  provider is deleted and must render gracefully instead of crashing. */
    fun getProviderInstanceOrNull(name: String): LlmProvider? = providerRegistry.getInstanceOrNull(name)

    private val scrollRequests = ScrollRequestCoordinator()
    private val selectionController: ConversationSelectionController by lazy {
        ConversationSelectionController(
            scope = viewModelScope,
            conversations = convRepo,
            registry = generationRegistry,
            defaultModel = settings.selectedModel,
            scrollRequests = scrollRequests,
            renderStore = { renderStore },
            clearConversationGraph = { conversationUi.clearConversationGraph() },
            clearPendingSystemPrompt = { _pendingSystemPromptId.value = null },
            clearPendingConversationSettings = { _pendingConversationSettings.value = null },
            abortRegeneration = { regenerationTransitions.abortCurrent() },
        )
    }

    /** Callback invoked when any send path (manual/queue/loop) accepts a message.
     *  ChatApp wires this to trigger a single haptics.confirm() for all three paths. */
    @Volatile var onSendAccepted: ((conversationId: String, messageId: String) -> Unit)? = null
    val animatedScrollRequest: StateFlow<AnimatedScrollRequest?> =
        scrollRequests.request

    /** One-shot: set when sendMessage creates a new conversation so the conversation-open
     *  auto-scroll skips once (the send's scroll-to-message already handles it), preventing
     *  a double scroll on the first message of a new chat. Consumed by ChatApp. */
    var suppressNextOpenScroll: Boolean
        get() = scrollRequests.suppressNextOpenScroll
        set(value) { scrollRequests.suppressNextOpenScroll = value }

    /** When true, draft write-backs are suppressed to prevent feedback loops while
     *  programmatically loading a stored draft into the composer field. */
    var loadingDraft: Boolean
        get() = scrollRequests.loadingDraft
        set(value) { scrollRequests.loadingDraft = value }

    fun triggerScrollToMessage(messageId: String? = null) {
        scrollRequests.requestMessage(currentConversationId.value, messageId)
    }

    fun triggerScrollToAbsoluteBottomAfter(conversationId: String, messageId: String) {
        scrollRequests.requestAbsoluteBottomAfter(conversationId, messageId)
    }

    fun triggerScrollToAttachedBottomAfter(conversationId: String, messageId: String) {
        scrollRequests.requestAbsoluteBottomAfter(
            conversationId = conversationId,
            messageId = messageId,
            attachedOnly = true,
        )
    }

    fun completeAnimatedScroll(requestId: Long) = scrollRequests.complete(requestId)

    val currentActiveModel: StateFlow<String> get() = selectionController.currentActiveModel

    fun getProviderForModel(modelId: String): String = providerRegistry.providerForModel(modelId)

    // ── Remote shell command confirmation gate ───────────────────────────
    /** Shell-command confirmation policy + pending-prompt handshake (see [ShellConfirmationController]). */
    val pendingShellCommand: StateFlow<ShellConfirmationController.PendingShellCommand?> get() = shellConfirmation.pendingShellCommand

    /** Called by the UI to resolve a pending confirmation. */
    fun resolveShellConfirmation(allow: Boolean, alwaysAllowServer: Boolean = false) = shellConfirmation.resolve(allow, alwaysAllowServer)

    fun setShellConfirmEnabled(enabled: Boolean) = shellConfirmation.setEnabled(enabled)
    val pendingQuestion get() = generationManager.askUserController.pendingQuestion
    fun resolveAskUser(answers: List<String>) = generationManager.askUserController.resolve(answers)
    fun cancelAskUser() = generationManager.askUserController.cancel()
    val planState get() = generationManager.planStateHolder.plans

    // ── Tasks (automation) ────────────────────────────────────
    val tasks: StateFlow<List<com.lxseek.chat.data.local.TaskEntity>> get() = taskManager.tasks
    val runningTaskIds: StateFlow<Set<String>> get() = taskManager.runningTaskIds

    fun executionsForTask(taskId: String) = taskManager.executionsForTask(taskId)
    fun executionSummariesForTask(taskId: String) = taskManager.executionSummariesForTask(taskId)
    suspend fun getTask(taskId: String) = taskManager.getTask(taskId)

    fun saveTask(task: com.lxseek.chat.data.local.TaskEntity) {
        viewModelScope.launch { taskManager.saveTask(task) }
    }

    fun deleteTask(taskId: String) {
        viewModelScope.launch { taskManager.deleteTask(taskId) }
    }

    fun runTaskNow(task: com.lxseek.chat.data.local.TaskEntity) = taskManager.runNow(task)

    // ── Auto Backup ───────────────────────────────────────────

    val conversations: StateFlow<List<ChatConversation>> = convRepo.getAllConversations()
        .catch { e ->
            DebugLog.e("ChatViewModel", "Failed to load conversations", e)
            emit(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val currentConversationId: StateFlow<String?> get() = selectionController.currentConversationId
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val currentConversation: StateFlow<ChatConversation?> = currentConversationId
        .flatMapLatest { id -> if (id == null) flowOf(null) else convRepo.observeConversation(id) }
        .catch { e ->
            DebugLog.e("ChatViewModel", "Failed to observe current conversation", e)
            emit(null)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val unreadGenerationAcknowledger = UnreadGenerationAcknowledger(
        currentConversation = currentConversation,
        conversations = convRepo,
        scope = viewModelScope,
    )
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val currentLoop: StateFlow<com.lxseek.chat.data.local.LoopEntity?> = currentConversationId
        .flatMapLatest { id ->
            if (id == null) {
                flowOf(null)
            } else {
                combine(
                    loopManager.loopForConversation(id),
                    loopManager.runningConversationIds,
                ) { loop, _ ->
                    // Visibility tracks the TIMER only. The card is a schedule indicator, so once
                    // the schedule is inactive it must disappear at once, even mid-cycle.
                    //
                    // It deliberately does not stay up for a running worker: an in-flight
                    // generation is already stoppable through the composer's Stop button, so
                    // keeping the card alive for that would make one control appear to own two
                    // unrelated lifetimes.
                    loop?.takeIf { it.active }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val runningLoopConversationIds: StateFlow<Set<String>> get() = loopManager.runningConversationIds

    fun stopCurrentLoop() {
        val id = currentConversationId.value ?: return
        viewModelScope.launch { loopManager.stopLoop(id) }
    }

    private val conversationUi = ConversationUiStateAssembler(
        conversations = convRepo,
        registry = generationRegistry,
        executionCoordinator = conversationExecutionCoordinator,
        currentConversationId = currentConversationId,
        appContext = appContext,
        scope = viewModelScope,
        onConversationLoadFailed = selectionController::failConversationLoad,
    )
    private val renderStore: ConversationRenderStore get() = conversationUi.renderStore
    val allMessages: StateFlow<List<ChatMessage>> = conversationUi.allMessages
    val loadedMessagesConversationId: StateFlow<String?> =
        conversationUi.loadedMessagesConversationId

    private val providerModelSync = ProviderModelSyncController(
        providers = providerRegistry,
        settings = settings,
        scope = viewModelScope,
    )
    private val providerModelSyncUi by lazy {
        ProviderModelSyncUiAdapter(
            controller = providerModelSync,
            text = ProviderModelSyncUiText(
                failureLabels = ModelSyncFailureLabels(
                    noModels = appContext.getString(R.string.sync_error_no_models),
                    timeout = appContext.getString(R.string.sync_error_timeout),
                    invalidResponse = appContext.getString(R.string.sync_error_invalid_response),
                    unknown = appContext.getString(R.string.unknown_error),
                ),
                globalProviderName = appContext.getString(R.string.models_title),
                successfulProviders = { count ->
                    appContext.getString(R.string.sync_success_providers, count)
                },
                noProviders = appContext.getString(R.string.sync_no_providers),
                completed = appContext.getString(R.string.sync_completed),
            ),
            publishMessage = { message -> _snackbarMessage.emit(SnackbarEvent(message)) },
        )
    }
    val isSyncingModels: StateFlow<Boolean> get() = providerModelSyncUi.isSyncing

    // replay=0: with replay=1 an Activity recreation (rotation) re-collected the flow and
    // re-showed the last snackbar. The 1-slot buffer keeps tryEmit lossless for slow collectors;
    // events emitted during the brief recreation gap are dropped rather than replayed stale.
    private val _snackbarMessage = MutableSharedFlow<SnackbarEvent>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val snackbarMessage = _snackbarMessage.asSharedFlow()
    fun emitSnackbar(message: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
        viewModelScope.launch { _snackbarMessage.emit(SnackbarEvent(message, actionLabel, onAction)) }
    }
    private val _conversationShareText = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val conversationShareText = _conversationShareText.asSharedFlow()

    private val _firstMessageCommitted = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val firstMessageCommitted = _firstMessageCommitted.asSharedFlow()

    private val _updateDialogData = MutableStateFlow<UpdateInfo?>(null)
    val updateDialogData: StateFlow<UpdateInfo?> = _updateDialogData.asStateFlow()
    fun dismissUpdateDialog() { _updateDialogData.value = null }
    fun showUpdateDialog(info: UpdateInfo) { _updateDialogData.value = info }

    private val _ttsPlayingMessageId = MutableStateFlow<String?>(null)
    val ttsPlayingMessageId: StateFlow<String?> = _ttsPlayingMessageId.asStateFlow()

    private fun playTtsForMessage(messageId: String, text: String, showFailureSnackbar: Boolean = false) =
        playTtsForMessageInternal(
            appContext, messageId, text,
            settings.ttsLanguage.value, settings.ttsSpeechRate.value,
            _ttsPlayingMessageId, _snackbarMessage, viewModelScope, showFailureSnackbar,
        )

    /** PDF / text-file preview state (see [MediaPreviewState]). */
    private val mediaPreview = MediaPreviewState()
    val previewPdfPages: StateFlow<List<String>> get() = mediaPreview.pdfPages
    val previewPdfIndex: StateFlow<Int> get() = mediaPreview.pdfIndex
    val previewFileContent: StateFlow<String?> get() = mediaPreview.fileContent
    val previewFileName: StateFlow<String?> get() = mediaPreview.fileName

    fun showPdfPreview(pages: List<String>, startIndex: Int) = mediaPreview.showPdf(pages, startIndex)
    fun showFilePreview(fileName: String, content: String) = mediaPreview.showFile(fileName, content)
    fun clearPreviews() = mediaPreview.clear()

    val messages: StateFlow<List<ChatMessage>> = conversationUi.messages
    val totalTokens: StateFlow<Int> = conversationUi.totalTokens
    val isLoading: StateFlow<Boolean> = conversationUi.isLoading
    val generatingInConversationId: StateFlow<String?> =
        conversationUi.generatingInConversationId

    /** Per-conversation generation state registry. Each conversation owns an independent
     *  ConversationGenerationState; the global loading/render mirrors
     *  below are now a MIRROR of whichever conversation is currently open (see init collectors). */
    private val generationCallbackOwner = Any()
    private val foregroundAutomationBridge by lazy {
        ForegroundAutomationBridgeController(
            currentConversationId = currentConversationId,
            send = generationController::sendMessageFromAutomationAwaitingCompletion,
            loadMessages = convRepo::getMessagesForConversationSnapshot,
            attach = taskExecutionEngine::attachForegroundSendBridge,
            detach = taskExecutionEngine::detachForegroundSendBridge,
        )
    }
    private val generationCallbacksAttached = Unit.also {
        generationRegistry.attachUiCallbacks(generationCallbackOwner) { state ->
            state.onActive = { conversationId ->
                // Publish synchronously with the slot claim so Stop and edit closure are immediate.
                conversationUi.markActive(conversationId)
            }
            state.onIdle = { conversationId ->
                conversationUi.markIdle(conversationId)
            }
            state.onStreamCommit = { conversationId, message ->
                conversationUi.commitTerminalStreamingMessage(conversationId, message)
                val voiceStreaming = voiceConversation.isConversationStreaming()
                if ((voiceStreaming || (settings.ttsEnabled.value && settings.ttsAutoPlay.value)) &&
                    conversationId == currentConversationId.value
                ) {
                    playTtsForMessage(message.id, message.text)
                }
            }
            state.onQueueDrainRequested = { settledState ->
                settledState.scope.launch {
                    generationController.drainQueuedAfterGeneration(settledState)
                }
            }
            state.onStopSettled = { settledState ->
                // After a Stop cleanly settles (STOPPED row persisted, slot released), drain
                // any queued sends into a fresh Run so accepted interventions are never dropped.
                settledState.scope.launch {
                    generationController.drainQueuedAfterStop(settledState)
                }
            }
        }
        // Register the connectivity callback so [NetworkMonitor.online] actually emits updates,
        // then bind it to the registry so every conversation's queued guidance auto-retries on
        // offline→online restoration. viewModelScope owns the collector jobs; onCleared
        // unregisters the callback and cancelling the scope tears down the subscriptions.
        networkMonitor.register()
        generationRegistry.bindNetworkMonitor(networkMonitor, viewModelScope)
    }

    /** Every conversation currently mutating its message tree through foreground generation or
     * headless Task/Loop execution. Drawer rows use this per-id set instead of the open
     * conversation's open UI loading mirror. */
    val generatingConversationIds: StateFlow<Set<String>> = combine(
        generationRegistry.activeConversationIds,
        conversationExecutionCoordinator.activeAutomationConversationIds,
    ) { foreground, automation ->
        foreground + automation
    }.catch { e ->
        DebugLog.e("ChatViewModel", "Failed to compute generating conversations", e)
        emit(emptySet())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private val generationStopAdapter by lazy {
        GenerationStopAdapter(
            currentConversationId = currentConversationId,
            registry = generationRegistry,
            renderStore = renderStore,
            finalizer = GenerationFinalizer(convRepo, ragManager::indexMessageForRag),
            failureText = {
                getApplication<Application>().getString(R.string.failed_to_generate)
            },
            onFailure = { message -> emitSnackbar(message) },
        )
    }

    val isSwitching: StateFlow<Boolean> get() = selectionController.isSwitching

    private val regenerationTransitions = RegenerationTransitionCoordinator()
    internal val regenerationTransition: StateFlow<RegenerationTransitionRequest?> =
        regenerationTransitions.request

    fun acknowledgeRegenerationFade(requestId: Long) {
        regenerationTransitions.acknowledgeFade(requestId)
    }

    fun acknowledgeRegenerationScroll(requestId: Long, success: Boolean) {
        regenerationTransitions.acknowledgeScroll(requestId, success)
    }

    fun completeRegenerationTransition(requestId: Long) {
        regenerationTransitions.complete(requestId)
    }

    val isNewChatMode: StateFlow<Boolean> get() = selectionController.isNewChatMode
    val newChatEntryId: StateFlow<Long> get() = selectionController.newChatEntryId
    val isTransitioningToNewChat: StateFlow<Boolean> get() = selectionController.isTransitioningToNewChat

    private val _pendingSystemPromptId = MutableStateFlow<String?>(null)
    val pendingSystemPromptId: StateFlow<String?> = _pendingSystemPromptId.asStateFlow()

    fun setPendingSystemPrompt(promptId: String?) {
        _pendingSystemPromptId.value = promptId
    }

    private val _pendingConversationSettings = MutableStateFlow<ConversationSettings?>(null)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val isCompacting: StateFlow<Boolean> = currentConversationId
        .flatMapLatest { conversationId ->
            if (conversationId == null) flowOf(false)
            else generationRegistry.getOrCreate(conversationId).compacting
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val compactPreview: StateFlow<String> = currentConversationId
        .flatMapLatest { conversationId ->
            if (conversationId == null) flowOf("")
            else generationRegistry.getOrCreate(conversationId).compactPreview
        }
        .catch { e ->
            DebugLog.e("ChatViewModel", "Failed to observe compact preview", e)
            emit("")
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val pendingConversationSettings: StateFlow<ConversationSettings?> = _pendingConversationSettings.asStateFlow()

    fun setPendingConversationSettings(settings: ConversationSettings?) {
        _pendingConversationSettings.value = settings
    }

    private val payloadBuilder by lazy {
        MessagePayloadBuilder(
            generationManager = generationManager,
            onSnackbar = { msg -> _snackbarMessage.emit(SnackbarEvent(msg)) },
        )
    }

    private val requestBuilder = GenerationRequestBuilder(
        settings = settings,
        convRepo = convRepo,
        memoryManager = memoryManager,
        providerRegistry = providerRegistry,
        ragManager = ragManager,
        appContext = appContext,
        pendingConversationSettings = _pendingConversationSettings,
        onSnackbar = { msg -> emitSnackbar(msg) },
    )

    private val generationController by lazy {
        MessageGenerationController(
            viewModelScope = viewModelScope,
            application = getApplication(),
            appContext = appContext,
            convRepo = convRepo,
            settings = settings,
            registry = generationRegistry,
            generationManagerProvider = { generationManager },
            requestBuilder = requestBuilder,
            payloadBuilder = payloadBuilder,
            providerRegistry = providerRegistry,
            localProvider = localProvider,
            executionCoordinator = conversationExecutionCoordinator,
            renderStore = renderStore,
            currentConversationId = currentConversationId,
            isNewChatMode = isNewChatMode,
            applyPendingConversationSettings = { conversationId ->
                _pendingConversationSettings.value?.let { pending ->
                    settings.setConversationSettings(conversationId, pending)
                    _pendingConversationSettings.value = null
                }
            },
            pendingSystemPromptId = _pendingSystemPromptId,
            currentActiveModel = currentActiveModel,
            messages = messages,
            onScrollToMessage = { id -> triggerScrollToMessage(id) },
            onScrollToAbsoluteBottomAfter = ::triggerScrollToAbsoluteBottomAfter,
            onScrollToAttachedBottomAfter = ::triggerScrollToAttachedBottomAfter,
            onSendAcceptedEvent = { convId, msgId ->
                // Feedback belongs to the conversation on screen. A send from the new-chat page
                // qualifies because that page becomes this very conversation, but its id is only
                // published after acceptance, so it is matched via isNewChatMode rather than by id.
                // Background automation on another conversation stays silent: from the user's point
                // of view nothing happened on screen.
                val currentId = currentConversationId.value
                val targetsOpenConversation = currentId == convId ||
                    (currentId == null && isNewChatMode.value)
                if (targetsOpenConversation) onSendAccepted?.invoke(convId, msgId)
            },
            onSnackbar = { msg -> emitSnackbar(msg) },
            onSnackbarSuspend = { msg -> _snackbarMessage.emit(SnackbarEvent(msg)) },
            onConversationCreatedBySend = { conversationId ->
                suppressNextOpenScroll = true
                _firstMessageCommitted.tryEmit(conversationId)
            },
            onConversationAcceptedBySend = selectionController::publishAcceptedConversation,
            onUserMessagePersisted = ragManager::indexMessageForRag,
            onTreeMutationStart = {
                selectionController.beginTreeMutation()
            },
            onTreeMutationSettling = selectionController::markTreeMutationReady,
            onTreeMutationFailed = selectionController::failTreeMutation,
            regenerationTransitions = regenerationTransitions,
            pauseConversationTasks = { conversationId -> loopManager.stopLoop(conversationId) },
        )
    }
    private val composerSendAdapter by lazy {
        ComposerSendAdapter(
            send = generationController::sendMessage,
            drafts = composerDrafts,
            scope = viewModelScope,
        )
    }

    fun updateConversationSetting(convId: String?, update: (ConversationSettings) -> ConversationSettings) {
        if (convId != null) {
            val current = settings.conversationSettings.value[convId] ?: ConversationSettings()
            settings.setConversationSettings(convId, update(current))
        } else {
            val current = _pendingConversationSettings.value ?: ConversationSettings()
            _pendingConversationSettings.value = update(current)
        }
    }

    val switchingScrollRequest: StateFlow<SwitchingScrollRequest?> =
        selectionController.switchingScrollRequest

    fun completeSwitchingScroll(requestId: Long): Boolean =
        selectionController.completeSwitchingScroll(requestId)

    fun failSwitchingScroll(requestId: Long, reason: String) =
        selectionController.failSwitchingScroll(requestId, reason)

    init {
        startInitJobs()
        unreadGenerationAcknowledger.start()
        conversationUi.start()

        // Loop cycles for the open conversation use the regular Send path; the bridge waits for
        // that exact durable turn and returns a typed result to the automation lease owner.
        foregroundAutomationBridge.start()

        TtsManager.init(appContext)
        // Route read-aloud through the provider-backed TTS model when the user has selected one;
        // otherwise TtsManager falls back to the system engine.
        TtsManager.networkTtsConfig = SpeechProviderWiring.networkTtsConfig(settings, providerRegistry)
        viewModelScope.launch {
            TtsManager.isPlaying.collect { playing ->
                if (!playing && _ttsPlayingMessageId.value != null) {
                    _ttsPlayingMessageId.value = null
                }
            }
        }
        viewModelScope.launch {
            TtsManager.isAvailable.collect { available ->
                if (!available && _ttsPlayingMessageId.value != null) {
                    // Engine went away (uninstalled or init failed after a successful start) —
                    // cancel any playing indicator so the UI doesn't stick on a mute Pause icon.
                    TtsManager.stop()
                    _ttsPlayingMessageId.value = null
                }
            }
        }
    }

    // ── Custom providers ──────────────────────────────────────
    // Settings persistence lives in SettingsRepository; ChatViewModel only maintains
    // the live in-memory provider instances (the `providers` map) via callbacks.
    fun addCustomProvider(
        name: String,
        baseUrl: String,
        protocol: com.lxseek.chat.data.CustomEndpointProtocol =
            com.lxseek.chat.data.CustomEndpointProtocol.OPENAI,
    ) = customModelConfiguration.addProvider(name, baseUrl, protocol)
    fun renameCustomProvider(oldName: String, newName: String) = customModelConfiguration.renameProvider(oldName, newName)
    fun updateCustomProviderProtocol(name: String, protocol: com.lxseek.chat.data.CustomEndpointProtocol) = customModelConfiguration.updateProviderProtocol(name, protocol)
    fun deleteCustomProvider(name: String) = customModelConfiguration.deleteProvider(name)

    fun updateCustomModel(
        oldModelId: String,
        provider: String,
        modelId: String,
        alias: String,
    ) {
        customModelConfiguration.updateModel(oldModelId, provider, modelId, alias)
    }

    fun deleteCustomModel(modelId: String) {
        customModelConfiguration.deleteModel(modelId)
    }

    fun getCurrentVersion(): String {
        return try { appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: "?" } catch (_: Exception) { "?" }
    }
    suspend fun checkForUpdates(): UpdateInfo? {
        return withContext(Dispatchers.IO) {
            UpdateChecker.check(getCurrentVersion())
        }
    }
    suspend fun semanticSearch(query: String, limit: Int = 20) = semanticSearchService.search(query, limit)
    suspend fun searchMessages(query: String, limit: Int = 20) = convRepo.searchMessages(query, limit)
    fun searchMessagesGlobally(query: String): Flow<List<GlobalSearchResult>> =
        convRepo.searchMessagesGlobally(query)
    fun addShellDevice(device: ShellDeviceConfig) {
        settings.addShellDevice(device)
    }
    fun updateShellDevice(device: ShellDeviceConfig) {
        settings.updateShellDevice(device)
    }

    private val sshHostKeyVerifier = SshHostKeyVerifier()
    private val remoteEmbeddingConnectionTester by lazy {
        RemoteEmbeddingConnectionTester(
            resolveApiKey = ragManager::resolveEmbeddingApiKey,
            resolveBaseUrl = ragManager::resolveEmbeddingBaseUrl,
        )
    }

    suspend fun verifySshHostKey(host: String, port: Int, user: String, password: String): Result<Pair<String, String>> = sshHostKeyVerifier.verify(host, port, user, password)

    suspend fun testRemoteEmbedding(modelName: String, baseUrl: String, apiKey: String = ""): String? = remoteEmbeddingConnectionTester.test(modelName, baseUrl, apiKey)

    fun createNewChat() = selectionController.createNewChat()

    fun selectConversation(id: String, hapticOnCompletion: Boolean = true) = selectionController.selectConversation(id, hapticOnCompletion)

    fun forkConversationFrom(messageId: String? = null) = conversationForkShareController.fork(messageId)

    fun shareConversation() = conversationForkShareController.shareConversation()

    fun shareGeneration(assistantMessageId: String) = conversationForkShareController.shareGeneration(assistantMessageId)

    fun shareMessages(messageIds: Set<String>) = conversationForkShareController.shareMessages(messageIds)

    private val messageExportController by lazy {
        MessageExportController(conversationForkShare, appContext, viewModelScope) { _snackbarMessage.emit(it) }
    }
    fun copyMessagesAsPlainText(messageIds: Set<String>) = messageExportController.copyMessagesAsPlainText(currentConversationId.value, messageIds)

    fun shareMessagesAsLongImage(messageIds: Set<String>, title: String) = messageExportController.shareMessagesAsLongImage(currentConversationId.value, messageIds, title)

    fun saveLongImageToGallery(messageIds: Set<String>, title: String) = messageExportController.saveLongImageToGallery(currentConversationId.value, messageIds, title)

    fun renameConversation(id: String, newTitle: String) {
        conversationLifecycleController.rename(id, newTitle)
    }

    fun generateTitle(conversationId: String) = generationController.generateTitle(conversationId)

    fun setConversationSystemPrompt(id: String, promptId: String?) {
        conversationLifecycleController.setSystemPrompt(id, promptId)
    }

    fun setActiveModel(model: String) = selectionController.setActiveModel(model)
    fun deleteConversation(id: String) = conversationLifecycleController.delete(id)

    /**
     * Deletes a message and all its descendants (BFS cascade).
     * Hidden tool_/result_ children are included in the cascade.
     * Attachments, embeddings, and branch selections are cleaned up.
     * Returns the count of deleted messages (for the confirmation dialog).
     */
    suspend fun compactContextManual(
        model: String,
        prompt: String,
        retainLogicalMessages: Int,
    ): CompactResult = generationController.compactManual(
        CompactRequest(model, prompt, retainLogicalMessages),
    )

    fun deleteMessage(messageId: String): Int {
        if (isSwitching.value) return 0
        return generationController.deleteMessage(messageId)
    }

    private val currentRuntimeFacade = CurrentConversationRuntimeFacade(
        currentConversationId = currentConversationId,
        registry = generationRegistry,
        scope = viewModelScope,
    )
    val queuedSends: StateFlow<List<QueuedSend>> get() = currentRuntimeFacade.queuedSends
    val isStopping: StateFlow<Boolean> get() = currentRuntimeFacade.isStopping

    fun removeQueuedSend(id: String) = currentRuntimeFacade.removeQueuedSend(id)
    fun stopGeneration() = generationStopAdapter.stopVisibleConversation()

    fun toggleTtsForMessage(message: ChatMessage) {
        val current = _ttsPlayingMessageId.value
        if (current == message.id) {
            TtsManager.stop()
            _ttsPlayingMessageId.value = null
            return
        }
        if (!settings.ttsEnabled.value) {
            settings.setTtsEnabled(true)
        }
        playTtsForMessage(message.id, message.text, showFailureSnackbar = true)
    }

    fun stopTts() {
        TtsManager.stop()
        _ttsPlayingMessageId.value = null
    }

    val voiceConversation = VoiceConversationController(
        scope = viewModelScope, appContext = appContext,
        voiceLanguageProvider = { settings.voiceLanguage.value },
        ttsAutoPlayOn = { settings.ttsEnabled.value && settings.ttsAutoPlay.value },
        isLoading = isLoading,
        sendMessage = { text -> sendMessage(text) },
        asrEnginePref = { settings.asrEnginePref.value },
        // Provider-backed ASR is wired from the selected ASR model (if any) via SpeechProviderWiring;
        // otherwise it degrades to the legacy asrRemote* fields, then to the active chat provider.
        whisperApiKey = SpeechProviderWiring.whisperApiKey(settings, providerRegistry),
        whisperBaseUrl = SpeechProviderWiring.whisperBaseUrl(settings, providerRegistry),
        whisperModel = SpeechProviderWiring.whisperModel(settings),
    )
    fun toggleVoiceConversation() = voiceConversation.toggle()
    fun stopVoiceConversation() = voiceConversation.stop()
    fun startSingleAsr() = voiceConversation.startSingleAsr()
    fun stopSingleAsr() = voiceConversation.stopSingleAsr()

    fun regenerate(messageId: String): Boolean = generationController.regenerate(messageId)

    fun switchBranch(parentId: String?, currentMessageId: String, direction: Int) = selectionController.switchBranch(parentId, currentMessageId, direction)

    suspend fun editMessage(messageId: String, newText: String): Boolean = generationController.editMessage(messageId, newText)
    suspend fun sendMessage(
        text: String,
        images: List<String> = emptyList(),
        attachments: List<SelectedAttachment> = emptyList(),
        onAccepted: suspend () -> Unit = {},
    ): SendAcceptance? {
        // Pre-check connectivity before entering the generation pipeline. Local (on-device)
        // models work offline, so the gate only applies to remote providers. Emitting a
        // snackbar here avoids creating a doomed ERROR message row that the user must dismiss.
        if (!networkMonitor.isOnline()) {
            emitSnackbar(appContext.getString(R.string.network_unavailable))
            return null
        }
        return composerSendAdapter.sendMessage(text, images, attachments, onAccepted)
    }

    suspend fun fetchModelsForProvider(name: String): List<String> = providerModelSyncUi.fetchModelsForProvider(name)

    fun computeProviderFingerprint(): String = providerModelSyncUi.computeFingerprint()

    fun fetchAvailableModels() = providerModelSyncUi.fetchAvailableModels()

    // ── Per-conversation draft persistence ─────────────────────

    suspend fun persistDraft(
        conversationId: String,
        expectedRevision: Long,
        text: String,
        attachments: List<SelectedAttachment>,
        explicitlyRemovedAttachments: List<SelectedAttachment> = emptyList(),
    ): DraftPersistResult = composerDrafts.persist(
        conversationId = conversationId,
        expectedRevision = expectedRevision,
        text = text,
        attachments = attachments,
        explicitlyRemovedAttachments = explicitlyRemovedAttachments,
    )

    suspend fun loadDraft(conversationId: String): LoadedComposerDraft = composerDrafts.load(conversationId)
}
