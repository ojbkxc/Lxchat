package com.lxseek.chat.viewmodel

import com.lxseek.chat.data.repository.ConversationRepository
import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.ConversationCommand
import com.lxseek.chat.model.RunEffectIdentity
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Persists terminal (STOPPED) message state to the DB after a generation is stopped. Kept separate
 * from per-conversation [ConversationGenerationState] (which owns no repos) so it can delegate
 * finalization without holding repository references.
 *
 * Runs on the supplied conversation-owned scope; conversation/Run/pass/effect identity comes from
 * the reducer's [RunEffectIdentity], NOT from live UI state. The completion echoes that exact
 * identity so stale or duplicate callbacks cannot mutate a later Run.
 */
class GenerationFinalizer(
    private val convRepo: ConversationRepository,
    private val onIndexMessageForRag: (messageId: String, text: String) -> Unit,
) {
    /**
     * Persist [messages] as STOPPED for [identity] on [scope]. Returns the launched job; a
     * finalization effect always identifies a durable Run, even when there is no message overlay.
     * [onFinalized] returns an identified result command reporting whether Room reached a terminal
     * state. Failure must not release the in-memory slot because the database's unique live-Run
     * slot is still unavailable.
     */
    fun launchStopFinalization(
        scope: CoroutineScope,
        identity: RunEffectIdentity,
        messages: List<ChatMessage>,
        onFinalized: suspend (ConversationCommand.PersistenceSettled) -> Unit = {},
    ): Job {
        val conversationId = identity.conversationId
        val runId = identity.runId
        val distinct = messages.distinctBy { it.id }
        return scope.launch {
            var finalized = false
            var lastFailure: Exception? = null
            val retryDelaysMs = longArrayOf(0L, 40L, 120L)
            for (retryDelayMs in retryDelaysMs) {
                if (retryDelayMs > 0L) delay(retryDelayMs)
                try {
                    convRepo.requestRunStop(runId)
                    finalized = convRepo.finishStoppedGeneration(distinct, runId)
                    if (finalized) break
                } catch (e: Exception) {
                    lastFailure = e
                }
            }
            if (!finalized) {
                val message =
                    "Failed to persist stopped generation after ${retryDelaysMs.size} attempts"
                if (lastFailure != null) DebugLog.e("LxChatVM", message, lastFailure)
                else DebugLog.e("LxChatVM", message)
            }
            onFinalized(
                ConversationCommand.PersistenceSettled(
                    identity = identity,
                    success = finalized,
                ),
            )
            // RAG is outside the Stop critical path and owns its own eligibility gate.
            if (finalized) {
                distinct.forEach { message ->
                    if (message.text.isNotBlank()) onIndexMessageForRag(message.id, message.text)
                }
            }
        }
    }
}
