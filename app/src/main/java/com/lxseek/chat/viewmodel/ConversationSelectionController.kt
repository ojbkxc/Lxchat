package com.lxseek.chat.viewmodel

import com.lxseek.chat.data.repository.ConversationRepository
import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.util.Constants
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/**
 * Owns the open-conversation projection and its mutually superseding transition Job.
 *
 * This controller can read a conversation runtime's busy projection, but cannot submit runtime
 * commands, write RunState, or execute generation effects. Its Room mutation is limited to the
 * selected Run/message branch transaction.
 */
internal class ConversationSelectionController(
    private val scope: CoroutineScope,
    private val conversations: ConversationRepository,
    private val registry: ConversationStateRegistry,
    defaultModel: StateFlow<String>,
    private val scrollRequests: ScrollRequestCoordinator,
    private val renderStore: () -> ConversationRenderStore,
    private val clearConversationGraph: () -> Unit,
    private val clearPendingSystemPrompt: () -> Unit,
    private val clearPendingConversationSettings: () -> Unit,
    private val abortRegeneration: () -> Unit,
    private val fadeDelay: suspend () -> Unit = { delay(SWITCH_OVERLAY_FADE_MS) },
) {
    private val switching = SwitchingCoordinator()
    private var switchingJob: Job? = null

    private val _currentConversationId = MutableStateFlow<String?>(null)
    val currentConversationId: StateFlow<String?> = _currentConversationId.asStateFlow()

    private val _activeModelOverride = MutableStateFlow<String?>(null)
    val currentActiveModel: StateFlow<String> = combine(
        _activeModelOverride,
        defaultModel,
    ) { active, fallback ->
        active ?: fallback
    }.stateIn(scope, SharingStarted.Eagerly, Constants.EXAMPLE_MODEL_ID)

    private val _isNewChatMode = MutableStateFlow(true)
    val isNewChatMode: StateFlow<Boolean> = _isNewChatMode.asStateFlow()

    private val _newChatEntryId = MutableStateFlow(1L)
    val newChatEntryId: StateFlow<Long> = _newChatEntryId.asStateFlow()

    private val _isTransitioningToNewChat = MutableStateFlow(false)
    val isTransitioningToNewChat: StateFlow<Boolean> =
        _isTransitioningToNewChat.asStateFlow()

    val isSwitching: StateFlow<Boolean> = switching.isSwitching
    val switchingScrollRequest: StateFlow<SwitchingScrollRequest?> = switching.request

    /** Publish a first Send only after its conversation/Run/message graph is durable. */
    fun publishAcceptedConversation(conversationId: String) {
        require(conversationId.isNotBlank())
        _currentConversationId.value = conversationId
        _isNewChatMode.value = false
    }

    fun replaceActiveModelReference(oldModelId: String, newModelId: String?) {
        if (_activeModelOverride.value == oldModelId) {
            _activeModelOverride.value = newModelId
        }
    }

    fun setActiveModel(model: String) {
        _activeModelOverride.value = model
        _currentConversationId.value?.let { conversationId ->
            scope.launch {
                conversations.getConversation(conversationId)?.let { current ->
                    conversations.upsertConversation(current.copy(modelId = model))
                }
            }
        }
    }

    fun createNewChat() {
        // Drawer and top-bar actions are the same no-op while already on New Chat.
        if (_isNewChatMode.value) return
        abortRegeneration()
        val previousJob = switchingJob
        val request = switching.beginNewChat()
        previousJob?.cancel()
        clearPendingSystemPrompt()
        _newChatEntryId.value += 1L
        _isNewChatMode.value = true
        _isTransitioningToNewChat.value = true
        scrollRequests.clear()
        switchingJob = scope.launch {
            try {
                fadeDelay()
                if (!switching.isCurrent(request.id)) return@launch
                _currentConversationId.value = null
                _activeModelOverride.value = null
                clearPendingConversationSettings()
                clearConversationGraph()
            } finally {
                if (switching.complete(request.id)) {
                    _isTransitioningToNewChat.value = false
                }
            }
        }
    }

    fun selectConversation(
        conversationId: String,
        hapticOnCompletion: Boolean = true,
    ) {
        if (
            _currentConversationId.value == conversationId &&
            !_isNewChatMode.value
        ) {
            return
        }
        abortRegeneration()
        val previousJob = switchingJob
        val request = switching.beginConversation(conversationId, hapticOnCompletion)
        previousJob?.cancel()
        _isTransitioningToNewChat.value = false
        scrollRequests.clear()
        switchingJob = scope.launch {
            try {
                fadeDelay()
                if (!switching.isCurrent(request.id)) return@launch
                val conversation = conversations.getConversation(conversationId)
                if (!switching.isCurrent(request.id)) return@launch
                if (conversation == null) {
                    failSwitchingScroll(request.id, "conversation disappeared")
                    return@launch
                }
                _isNewChatMode.value = false
                _currentConversationId.value = conversationId
                _activeModelOverride.value = conversation.modelId
                switching.markConversationReady(request.id)
            } catch (error: CancellationException) {
                if (switching.isCurrent(request.id)) {
                    failSwitchingScroll(request.id, "conversation switch cancelled")
                }
                throw error
            } catch (error: Exception) {
                DebugLog.e(
                    "ConversationSelection",
                    "Failed to select conversation $conversationId",
                    error,
                )
                failSwitchingScroll(request.id, "conversation load failed")
            }
        }
    }

    fun switchBranch(parentId: String?, currentMessageId: String, direction: Int) {
        if (switching.isSwitching.value) return
        val conversationId = _currentConversationId.value ?: return
        val state = registry.getOrCreate(conversationId)
        if (state.generating.value) return
        val store = renderStore()
        val currentAnchor = store.allMessages.firstOrNull { it.id == currentMessageId } ?: return
        // Edit branches are USER siblings; Regenerate branches are MODEL siblings.
        val siblings = store.allMessages.filter {
            it.parentId == parentId &&
                it.participant == currentAnchor.participant &&
                !it.id.startsWith(Constants.TOOL_MSG_PREFIX) &&
                !it.id.startsWith(Constants.RESULT_MSG_PREFIX)
        }.sortedWith(compareBy<ChatMessage> { it.timestamp }.thenBy { it.id })
        if (siblings.size < 2) return
        var currentIndex = siblings.indexOfFirst { it.id == currentMessageId }
        if (currentIndex == -1) {
            val selectedId = store.selectedChildren[parentId]
            currentIndex = siblings.indexOfFirst { it.id == selectedId }
        }
        if (currentIndex == -1) return
        val newIndex = (currentIndex + direction).coerceIn(0, siblings.size - 1)
        if (newIndex == currentIndex) return
        val parentRunId = parentId?.let { id ->
            store.allMessages.firstOrNull { it.id == id }?.runId
        }

        val previousJob = switchingJob
        val request = switching.beginTreeMutation(conversationId)
        previousJob?.cancel()
        switchingJob = scope.launch {
            try {
                fadeDelay()
                if (!switching.isCurrent(request.id)) return@launch
                state.queueMutationMutex.withLock {
                    if (
                        state.generating.value ||
                        _currentConversationId.value != conversationId
                    ) {
                        switching.complete(request.id)
                        return@withLock
                    }
                    val newSelections = store.selectedChildren.toMutableMap()
                    val targetMessage = siblings[newIndex]
                    val targetRunId = targetMessage.runId ?: run {
                        switching.complete(request.id)
                        return@withLock
                    }
                    newSelections[parentId] = targetMessage.id
                    conversations.selectRunBranch(
                        conversationId = conversationId,
                        parentRunId = parentRunId,
                        runId = targetRunId,
                        messageSelections = newSelections,
                    )
                    store.setSelectedChildren(newSelections)
                    switching.markTreeMutationReady(request.id, targetMessage.id)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                DebugLog.e("ConversationSelection", "Failed to switch Run branch", error)
                switching.complete(request.id)
            }
        }
    }

    /** Begins the same UI transition for edit/delete services without exposing the coordinator. */
    suspend fun beginTreeMutation(): Long? {
        val request = _currentConversationId.value?.let(switching::beginTreeMutation)
        fadeDelay()
        return request?.id
    }

    fun markTreeMutationReady(requestId: Long?, targetMessageId: String?) {
        requestId?.let { switching.markTreeMutationReady(it, targetMessageId) }
    }

    fun failTreeMutation(requestId: Long?) {
        requestId?.let { switching.complete(it) }
    }

    fun completeSwitchingScroll(requestId: Long): Boolean = switching.complete(requestId)

    fun failSwitchingScroll(requestId: Long, reason: String) {
        if (!switching.isCurrent(requestId)) return
        DebugLog.e("ConversationSelection", "Switching scroll did not settle: $reason")
        switching.complete(requestId)
    }

    fun failConversationLoad(conversationId: String) {
        val request = switching.request.value ?: return
        if (
            request.kind == SwitchingRequestKind.CONVERSATION &&
            request.conversationId == conversationId
        ) {
            failSwitchingScroll(request.id, "conversation projection failed")
        }
    }

    private companion object {
        const val SWITCH_OVERLAY_FADE_MS = 200L
    }
}
