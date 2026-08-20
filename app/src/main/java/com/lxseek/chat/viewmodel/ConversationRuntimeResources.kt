package com.lxseek.chat.viewmodel

import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.MessageStatus
import com.lxseek.chat.model.RunEffect
import com.lxseek.chat.model.RunEffectIdentity
import com.lxseek.chat.model.RunState
import com.lxseek.chat.model.RuntimeRunIdentity
import com.lxseek.chat.model.SlotReleaseReason
import com.lxseek.chat.model.Transition
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

internal data class RuntimeResourceEvents(
    val activated: Boolean = false,
    val released: Boolean = false,
)

/**
 * Sole owner of one conversation's process resources and UI projection.
 *
 * The runtime host serializes calls under its generation lock. This owner never calls the reducer,
 * changes [RunState], releases a slot, or chooses a subsequent effect. It only applies resource
 * consequences of an already accepted [Transition] and manages handles installed by the host.
 */
internal class ConversationRuntimeResources {
    val streamScope = StreamScope()

    private val _streamingMessage = MutableStateFlow<ChatMessage?>(null)
    val streamingMessage: StateFlow<ChatMessage?> = _streamingMessage.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _generating = MutableStateFlow(false)
    val generating: StateFlow<Boolean> = _generating.asStateFlow()

    private val _stopping = MutableStateFlow(false)
    val stopping: StateFlow<Boolean> = _stopping.asStateFlow()

    private val _compacting = MutableStateFlow(false)
    val compacting: StateFlow<Boolean> = _compacting.asStateFlow()

    private val _compactPreview = MutableStateFlow("")
    val compactPreview: StateFlow<String> = _compactPreview.asStateFlow()
    private var compactPreviewIdentity: RunEffectIdentity? = null

    private var generationJob: Job? = null
    private var uiGenToken = 0L
    private val persistId = AtomicLong(0L)
    private var suppressNextQueueDrain = false

    fun captureUiToken(): Long = uiGenToken

    fun nextUiToken(): Long = uiGenToken + 1

    fun isCurrentToken(token: Long): Boolean = uiGenToken == token

    fun nextPersistId(): Long = persistId.incrementAndGet()

    fun isLatestPersist(id: Long): Boolean = persistId.get() == id

    fun currentGenerationJob(): Job? = generationJob

    fun installGenerationJob(job: Job): Boolean {
        if (generationJob != null) return false
        generationJob = job
        return true
    }

    fun activate(identity: RuntimeRunIdentity, loading: Boolean) {
        uiGenToken = identity.ownerToken
        _isLoading.value = loading
        _generating.value = true
        _stopping.value = false
    }

    fun applyMailboxEffects(
        transition: Transition,
        currentState: RunState,
    ): RuntimeResourceEvents {
        check(transition.accepted) { "Rejected transitions cannot mutate runtime resources" }
        var activated = false
        var released = false

        transition.effects.filterIsInstance<RunEffect.RunCompact>()
            .singleOrNull()
            ?.let { effect ->
                val compactState = currentState as? RunState.Compacting
                    ?: error("RunCompact must enter Compacting")
                check(compactState.effectIdentity == effect.identity)
                check(compactState.compactRunId == effect.compactRunId)
                check(compactState.mode == effect.mode)
                compactPreviewIdentity = effect.identity
                _compactPreview.value = ""
                _compacting.value = true
            }
        if (currentState !is RunState.Compacting && _compacting.value) {
            compactPreviewIdentity = null
            _compactPreview.value = ""
            _compacting.value = false
        }

        transition.effects.filterIsInstance<RunEffect.PersistAcceptedInput>()
            .singleOrNull()
            ?.let { effect ->
                val preparing = currentState as? RunState.Preparing
                    ?: error("Accepted input persistence must enter Preparing")
                check(preparing.inputEffectIdentity == effect.identity)
                activate(preparing.ownerIdentity, loading = false)
                activated = true
            }

        transition.effects.filterIsInstance<RunEffect.CancelProviderPass>()
            .singleOrNull()
            ?.let { effect ->
                check(uiGenToken == effect.identity.ownerToken)
                val stopped = _streamingMessage.value?.copy(status = MessageStatus.STOPPED)
                check(stopped == null || effect.identity.runId != null) {
                    "A streaming Stop effect requires a bound Run"
                }
                // Revoke DB/UI ownership before cancellation can unwind provider finally blocks.
                persistId.incrementAndGet()
                uiGenToken += 1
                _streamingMessage.value = stopped
                if (currentState is RunState.Stopping) {
                    _isLoading.value = true
                    _generating.value = true
                    _stopping.value = true
                }
            }

        transition.effects.filterIsInstance<RunEffect.ReleaseSlot>()
            .singleOrNull()
            ?.let { release ->
                check(currentState is RunState.Idle) {
                    "ReleaseSlot resource cleanup requires an Idle runtime state"
                }
                if (release.reason == SlotReleaseReason.STOP_BARRIERS_SETTLED) {
                    suppressNextQueueDrain = false
                }
                release()
                released = true
            }

        return RuntimeResourceEvents(activated = activated, released = released)
    }

    fun stoppableOverlay(currentState: RunState): ChatMessage? = _streamingMessage.value
        ?.takeUnless {
            currentState is RunState.Idle ||
                currentState is RunState.Compacting && currentState.resumeIdentity == null ||
                currentState is RunState.Finalizing && !currentState.persistenceFailureReported
        }
        ?.copy(status = MessageStatus.STOPPED)

    fun streamUpdate(uiToken: Long, message: ChatMessage) {
        if (this.uiGenToken == uiToken) _streamingMessage.value = message
    }

    fun loadingChange(uiToken: Long, value: Boolean) {
        if (this.uiGenToken == uiToken) _isLoading.value = value
    }

    fun appendCompactPreview(identity: RunEffectIdentity, delta: String): Boolean {
        if (delta.isEmpty() || compactPreviewIdentity != identity) return false
        _compactPreview.value += delta
        return true
    }

    fun streamMessageForClear(uiToken: Long): ChatMessage? = _streamingMessage.value
        ?.takeIf { this.uiGenToken == uiToken && it.status != MessageStatus.STOPPED }

    fun clearStreamingMessage() {
        _streamingMessage.value = null
    }

    fun clearStoppedOverlay() {
        if (_streamingMessage.value?.status == MessageStatus.STOPPED) {
            _streamingMessage.value = null
        }
    }

    fun deferNextQueueDrain() {
        suppressNextQueueDrain = true
    }

    fun consumeQueueDrainPermission(): Boolean {
        val allowed = !suppressNextQueueDrain
        suppressNextQueueDrain = false
        return allowed
    }

    fun cancelStreamsAnd(job: Job?) {
        streamScope.cancelAll()
        job?.cancel()
    }

    private fun release() {
        generationJob = null
        _isLoading.value = false
        _generating.value = false
        _stopping.value = false
        compactPreviewIdentity = null
        _compactPreview.value = ""
        _compacting.value = false
    }
}
