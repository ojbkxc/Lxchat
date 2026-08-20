package com.lxseek.chat.viewmodel

import android.content.Context
import com.lxseek.chat.automation.ConversationExecutionCoordinator
import com.lxseek.chat.data.repository.ConversationRepository
import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Combines the open conversation's Room graph and runtime overlay into stable UI projections.
 *
 * [ConversationRenderStore] remains the atomic projection store. This assembler owns only the
 * open-conversation collectors and mirror values; it cannot submit runtime commands or mutate a
 * Run. Its single collection job is owned by the injected ViewModel scope.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class ConversationUiStateAssembler(
    private val conversations: ConversationRepository,
    private val registry: ConversationStateRegistry,
    private val executionCoordinator: ConversationExecutionCoordinator,
    private val currentConversationId: StateFlow<String?>,
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val projectionDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val onConversationLoadFailed: (String) -> Unit = {},
) {
    val renderStore = ConversationRenderStore()

    val allMessages: StateFlow<List<ChatMessage>> = renderStore.snapshot
        .map { it.allMessages }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val messages: StateFlow<List<ChatMessage>> = renderStore.snapshot
        .mapLatest { snapshot ->
            withContext(projectionDispatcher) {
                ConversationUiState.resolvePath(
                    snapshot.allMessages,
                    snapshot.streamingMessage,
                    snapshot.selectedChildren,
                )
            }
        }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val totalTokens: StateFlow<Int> = renderStore.snapshot
        .map { snapshot -> snapshot.allMessages.sumOf { it.tokenCount } }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    private val _loadedMessagesConversationId = MutableStateFlow<String?>(null)
    val loadedMessagesConversationId: StateFlow<String?> =
        _loadedMessagesConversationId.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _generatingInConversationId = MutableStateFlow<String?>(null)
    val generatingInConversationId: StateFlow<String?> =
        _generatingInConversationId.asStateFlow()

    private val generationMirror = ConversationGenerationMirror(
        currentConversationId = currentConversationId,
        onSnapshot = { conversationId, snapshot ->
            renderStore.setStreamingMessage(snapshot.streamingMessage)
            _isLoading.value = snapshot.isLoading
            _generatingInConversationId.value =
                if (snapshot.isGenerating) conversationId else null
        },
    )

    private var collectionJob: Job? = null

    fun start() {
        if (collectionJob != null) return
        collectionJob = scope.launch {
            currentConversationId.collectLatest { id ->
                _loadedMessagesConversationId.value = null
                if (id != null) {
                    try {
                        collectConversation(id)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        DebugLog.e("ConversationUi", "Failed to load conversation projection", error)
                        if (currentConversationId.value == id) {
                            // The store deliberately retains the previous conversation under the
                            // switching cover until the target's first atomic snapshot arrives.
                            // A real load failure must retire that protected snapshot before the
                            // owner releases the cover, or the old graph/loading mirror would be
                            // exposed under the newly selected conversation.
                            clearAllProjection()
                            onConversationLoadFailed(id)
                        }
                    }
                } else {
                    clearAllProjection()
                }
            }
        }
    }

    fun markActive(conversationId: String) {
        if (currentConversationId.value == conversationId) {
            _isLoading.value = true
            _generatingInConversationId.value = conversationId
        }
    }

    fun markIdle(conversationId: String) {
        if (currentConversationId.value == conversationId) {
            _isLoading.value = false
            _generatingInConversationId.value = null
        }
    }

    fun commitTerminalStreamingMessage(conversationId: String, message: ChatMessage) {
        if (currentConversationId.value == conversationId) {
            renderStore.commitTerminalStreamingMessage(message)
        }
    }

    fun clearConversationGraph() {
        renderStore.clear()
        _loadedMessagesConversationId.value = null
    }

    private fun clearAllProjection() {
        clearConversationGraph()
        _isLoading.value = false
        _generatingInConversationId.value = null
    }

    private suspend fun collectConversation(id: String) = coroutineScope {
        conversations.ensureRunRecovery()
        val state = registry.getOrCreate(id)
        val automationRunning =
            id in executionCoordinator.activeAutomationConversationIds.value
        if (!state.generating.value && !automationRunning) {
            conversations.fixStuckMessages(id)
        }

        val conversation = conversations.getConversation(id)
        val restoredChildren = withContext(projectionDispatcher) {
            conversation?.selectedBranchesJson?.let { raw ->
                runCatching {
                    Json.decodeFromString<Map<String, String>>(raw)
                        .mapKeys { (key, _) -> if (key == "null") null else key }
                }.getOrNull()
            }.orEmpty()
        }
        var generationMirrorStarted = false
        state.streamingMessage
            .map { message -> message?.id }
            .distinctUntilChanged()
            .flatMapLatest { streamingMessageId ->
                conversations.getUiMessagesForConversation(id, streamingMessageId)
            }
            .distinctUntilChanged()
            .mapLatest { entities ->
                withContext(projectionDispatcher) {
                    entities.map { entity -> entity.toUiChatMessage(appContext) }
                }
            }
            .collect { mapped ->
                if (!generationMirrorStarted) {
                    renderStore.replaceConversation(
                        allMessages = mapped,
                        selectedChildren = restoredChildren,
                    )
                } else {
                    renderStore.setAllMessages(mapped)
                }
                _loadedMessagesConversationId.value = id
                if (!generationMirrorStarted) {
                    generationMirrorStarted = true
                    generationMirror.publishCurrent(id, state)
                    launch {
                        generationMirror.collect(id, state)
                    }
                }
            }
    }
}
