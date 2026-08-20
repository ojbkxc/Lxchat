package com.lxseek.chat.viewmodel

import com.lxseek.chat.api.GenerationError
import com.lxseek.chat.api.LlmProvider
import com.lxseek.chat.api.ProviderConfig
import com.lxseek.chat.api.StreamEvent
import com.lxseek.chat.api.util.safeWireToolCallId
import com.lxseek.chat.api.util.safeWireToolName
import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.RunEffectIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal sealed interface ProviderPassOutcome {
    val identity: RunEffectIdentity

    data class CompletedText(
        override val identity: RunEffectIdentity,
    ) : ProviderPassOutcome

    data class CompletedToolCalls(
        override val identity: RunEffectIdentity,
        val calls: List<StreamEvent.ToolCallRequest>,
    ) : ProviderPassOutcome {
        init {
            require(calls.isNotEmpty())
        }
    }

    data class Truncated(
        override val identity: RunEffectIdentity,
        val error: GenerationError.OutputTruncated,
    ) : ProviderPassOutcome

    data class Failed(
        override val identity: RunEffectIdentity,
        val error: GenerationError,
    ) : ProviderPassOutcome

    data class Cancelled(
        override val identity: RunEffectIdentity,
    ) : ProviderPassOutcome
}

/**
 * Executes exactly one Provider request and closes it into an identity-bearing outcome.
 *
 * Providers remain responsible for protocol-specific semantic termination validation and retry.
 * This boundary adds consumer-side fail-closed validation: live tool progress may reach the UI,
 * but no call becomes authoritative unless the completed batch has unique, complete metadata.
 */
internal class ProviderPassRunner(
    private val json: Json = Json,
) {
    suspend fun run(
        identity: RunEffectIdentity,
        provider: LlmProvider,
        messages: List<ChatMessage>,
        config: ProviderConfig,
        onEvent: suspend (StreamEvent) -> Unit,
    ): ProviderPassOutcome {
        val completedCalls = mutableListOf<StreamEvent.ToolCallRequest>()
        val openToolStreams = linkedSetOf<String>()
        var providerError: GenerationError? = null
        var sawEmptyToolBatch = false

        try {
            provider.generateResponse(messages, config).collect { event ->
                when (event) {
                    is StreamEvent.ToolCallUpdate -> openToolStreams += event.streamKey
                    is StreamEvent.ToolCallRequest -> {
                        completedCalls += event
                        openToolStreams -= event.streamKey
                    }
                    is StreamEvent.ToolCallsRequest -> {
                        if (event.calls.isEmpty()) sawEmptyToolBatch = true
                        event.calls.forEach { call ->
                            completedCalls += call
                            openToolStreams -= call.streamKey
                        }
                    }
                    is StreamEvent.Error -> if (providerError == null) {
                        providerError = event.error
                    }
                    is StreamEvent.TextChunk,
                    is StreamEvent.ThoughtChunk,
                    is StreamEvent.UsageUpdate,
                    is StreamEvent.Retrying,
                    -> Unit
                }
                try {
                    onEvent(event)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    throw EventConsumerException(error)
                }
                if (event is StreamEvent.Error) {
                    throw ProviderPassClosedException()
                }
            }
        } catch (cancelled: CancellationException) {
            return ProviderPassOutcome.Cancelled(identity)
        } catch (consumerFailure: EventConsumerException) {
            throw consumerFailure.original
        } catch (_: ProviderPassClosedException) {
            return errorOutcome(identity, checkNotNull(providerError))
        } catch (providerFailure: Exception) {
            providerError?.let { error ->
                return errorOutcome(identity, error)
            }
            val error = GenerationError.Unknown(providerFailure)
            onEvent(StreamEvent.Error(error))
            return ProviderPassOutcome.Failed(identity, error)
        }

        providerError?.let { error ->
            return errorOutcome(identity, error)
        }

        validateCompletedTools(completedCalls, openToolStreams, sawEmptyToolBatch)?.let { error ->
            onEvent(StreamEvent.Error(error))
            return ProviderPassOutcome.Failed(identity, error)
        }

        return if (completedCalls.isEmpty()) {
            ProviderPassOutcome.CompletedText(identity)
        } else {
            ProviderPassOutcome.CompletedToolCalls(identity, completedCalls.toList())
        }
    }

    private fun validateCompletedTools(
        calls: List<StreamEvent.ToolCallRequest>,
        openToolStreams: Set<String>,
        sawEmptyToolBatch: Boolean,
    ): GenerationError? {
        val invalidCause = when {
            sawEmptyToolBatch -> "Provider returned an empty tool call batch"
            openToolStreams.isNotEmpty() -> "Provider ended with incomplete tool metadata"
            calls.map { it.id }.distinct().size != calls.size ->
                "Provider returned duplicate tool call ids"
            calls.map { it.streamKey }.distinct().size != calls.size ->
                "Provider returned duplicate tool stream identities"
            calls.any { it.streamKey.isBlank() } ->
                "Provider returned an invalid tool stream identity"
            calls.any { !it.id.matches(safeWireToolCallId) } ->
                "Provider returned an invalid tool call id"
            calls.any { !it.name.matches(safeWireToolName) } ->
                "Provider returned an invalid or incomplete tool name"
            calls.any { call ->
                runCatching {
                    json.parseToJsonElement(call.arguments.ifBlank { "{}" }) is JsonObject
                }.getOrDefault(false).not()
            } -> "Provider returned incomplete tool arguments"
            else -> null
        } ?: return null
        return GenerationError.SseParse(rawLine = "tool_calls", cause = invalidCause)
    }

    private fun errorOutcome(
        identity: RunEffectIdentity,
        error: GenerationError,
    ): ProviderPassOutcome = when (error) {
        is GenerationError.OutputTruncated -> ProviderPassOutcome.Truncated(identity, error)
        else -> ProviderPassOutcome.Failed(identity, error)
    }

    private class EventConsumerException(val original: Exception) : RuntimeException(original)
    private class ProviderPassClosedException : RuntimeException()
}
