package com.lxseek.chat.viewmodel

import com.lxseek.chat.api.LlmProvider
import com.lxseek.chat.api.ProviderConfig
import com.lxseek.chat.api.StreamEvent
import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.ProviderPassResult
import com.lxseek.chat.model.RunEffect
import com.lxseek.chat.model.RunEffectIdentity
import kotlinx.coroutines.CancellationException

internal data class ProviderPassExecutionRequest(
    val proposedIdentity: RunEffectIdentity,
    val provider: LlmProvider,
    val messages: List<ChatMessage>,
    val config: ProviderConfig,
)

internal data class ProviderPassExecutionCallbacks(
    val requestEffect: suspend (RunEffectIdentity) -> RunEffect.StartProviderPass?,
    val returnConsumerFailure: suspend (RunEffectIdentity, ProviderPassResult) -> Unit,
    val onFirstEvent: (() -> Unit)?,
    val onEvent: suspend (StreamEvent) -> Unit,
)

/** Executes exactly one mailbox-authorized, protocol-validated Provider pass. */
internal class ProviderPassEffectExecutor(
    private val runner: ProviderPassRunner = ProviderPassRunner(),
) {
    suspend fun execute(
        request: ProviderPassExecutionRequest,
        callbacks: ProviderPassExecutionCallbacks,
    ): ProviderPassOutcome {
        val startEffect = callbacks.requestEffect(request.proposedIdentity)
            ?.takeIf { it.identity == request.proposedIdentity }
            ?: throw CancellationException(
                "Provider pass ${request.proposedIdentity.effectId} is no longer authorized",
            )
        var firstEventPending = callbacks.onFirstEvent != null
        try {
            return runner.run(
                identity = startEffect.identity,
                provider = request.provider,
                messages = request.messages,
                config = request.config,
            ) { event ->
                if (firstEventPending) {
                    firstEventPending = false
                    callbacks.onFirstEvent?.invoke()
                }
                callbacks.onEvent(event)
            }
        } catch (error: Exception) {
            // Runner normally closes failures into an outcome. A consumer failure must still close
            // the exact Running pass so the mailbox never retains a phantom Provider operation.
            callbacks.returnConsumerFailure(startEffect.identity, ProviderPassResult.FAILED)
            throw error
        }
    }
}

internal fun ProviderPassOutcome.resultType(): ProviderPassResult = when (this) {
    is ProviderPassOutcome.CompletedText -> ProviderPassResult.COMPLETED_TEXT
    is ProviderPassOutcome.CompletedToolCalls -> ProviderPassResult.COMPLETED_TOOL_CALLS
    is ProviderPassOutcome.Truncated -> ProviderPassResult.TRUNCATED
    is ProviderPassOutcome.Failed -> ProviderPassResult.FAILED
    is ProviderPassOutcome.Cancelled -> ProviderPassResult.CANCELLED
}
