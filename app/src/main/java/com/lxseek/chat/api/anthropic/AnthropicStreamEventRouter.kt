package com.lxseek.chat.api.anthropic

import com.lxseek.chat.api.GenerationError
import com.lxseek.chat.api.StreamEvent
import com.lxseek.chat.api.util.ProviderRetryPolicy
import com.lxseek.chat.api.util.ToolArgumentAccumulator
import com.lxseek.chat.api.util.safeWireToolCallId
import com.lxseek.chat.api.util.safeWireToolName
import com.lxseek.chat.model.TokenUsage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal class AnthropicStreamEventRouter {
    private data class ToolBlock(
        val streamKey: String,
        val id: String?,
        val name: String,
        val arguments: ToolArgumentAccumulator,
    )

    private val blockTypes = mutableMapOf<Int, String>()
    private val toolBlocks = mutableMapOf<Int, ToolBlock>()
    private val thinkingSignatures = mutableMapOf<Int, String?>()
    private val completedToolCallIds = mutableSetOf<String>()
    private var lastBlockIndex = -1
    private var syntheticIndex = 0
    private var inputTokens: Int? = null
    private var cacheCreationInputTokens = 0
    private var cacheReadInputTokens = 0

    /** A `message_stop` event was received. */
    internal var messageStopReceived = false
        private set

    /** Normalized `stop_reason`, read from `message_delta.delta` per protocol. */
    internal var stopReason: String? = null
        private set

    internal var toolUseBlockStarts = 0
        private set

    /** Failure the provider reported inside the 200 stream. */
    internal var streamError: GenerationError? = null
        private set

    /** True once the router has already emitted a fatal [StreamEvent.Error] itself. */
    internal var reportedError = false
        private set

    /**
     * Either terminal signal proves the message ended semantically rather than the socket merely
     * closing. Non-compliant relays sometimes omit `message_stop` but still report `stop_reason`.
     */
    internal val sawTerminalMarker: Boolean
        get() = messageStopReceived || stopReason != null

    /** A `tool_use` block was opened and never closed. */
    internal val toolCallInFlight: Boolean
        get() = toolBlocks.isNotEmpty()

    fun route(event: AnthropicStreamEvent): List<StreamEvent> = buildList {
        when (event.type) {
            "message_start" -> {
                event.message?.usage?.let { usage ->
                    inputTokens = usage.inputTokens?.coerceAtLeast(0) ?: inputTokens
                    cacheCreationInputTokens =
                        usage.cacheCreationInputTokens?.coerceAtLeast(0) ?: 0
                    cacheReadInputTokens =
                        usage.cacheReadInputTokens?.coerceAtLeast(0) ?: 0
                }
            }

            "content_block_start" -> {
                val block = event.contentBlock ?: return@buildList
                val index = indexForStart(event.index)
                blockTypes[index] = block.type
                when (block.type) {
                    "text" -> block.text?.takeIf(String::isNotEmpty)?.let {
                        add(StreamEvent.TextChunk(it))
                    }

                    "thinking" -> {
                        thinkingSignatures[index] = block.signature
                        block.thinking?.takeIf(String::isNotEmpty)?.let {
                            add(StreamEvent.ThoughtChunk(it, signature = block.signature))
                        }
                    }

                    "tool_use" -> {
                        toolUseBlockStarts++
                        val initialArguments = block.input
                            ?.takeUnless { it.isEmpty() }
                            ?.toString()
                            .orEmpty()
                        val tool = ToolBlock(
                            streamKey = block.id ?: "call_stream_${java.util.UUID.randomUUID()}",
                            id = block.id,
                            name = block.name.orEmpty(),
                            arguments = ToolArgumentAccumulator(initialArguments),
                        )
                        toolBlocks[index] = tool
                        // Tool identity is complete at block start; the UI segment must exist
                        // before the first input_json_delta is written.
                        add(tool.updateEvent())
                    }
                }
            }

            "content_block_delta" -> {
                val index = indexForContinuation(event.index)
                val delta = event.delta ?: return@buildList
                when (delta.type) {
                    "input_json_delta" -> {
                        val tool = toolBlocks[index] ?: return@buildList
                        tool.arguments.append(delta.partialJson)
                        add(tool.updateEvent())
                    }

                    "signature_delta" -> {
                        val signature = delta.signature?.takeIf(String::isNotBlank)
                            ?: return@buildList
                        thinkingSignatures[index] = signature
                        add(StreamEvent.ThoughtChunk(thought = "", signature = signature))
                    }

                    "thinking_delta" -> {
                        delta.thinking?.takeIf(String::isNotEmpty)?.let {
                            add(
                                StreamEvent.ThoughtChunk(
                                    thought = it,
                                    signature = thinkingSignatures[index],
                                )
                            )
                        }
                    }

                    "text_delta" -> {
                        if (blockTypes[index] == "text") {
                            delta.text?.takeIf(String::isNotEmpty)?.let {
                                add(StreamEvent.TextChunk(it))
                            }
                        }
                    }

                    else -> routeUntypedDelta(index, delta, this)
                }
            }

            "content_block_stop" -> {
                val index = indexForContinuation(event.index)
                toolBlocks.remove(index)?.let { tool ->
                    val invalidCause = tool.invalidCompletionCause()
                    if (invalidCause == null) {
                        completedToolCallIds += tool.id ?: tool.streamKey
                        add(tool.completeEvent())
                    } else {
                        // A closed block is executable only when BOTH identity and arguments are
                        // complete. A stop marker cannot turn truncated JSON into a valid call.
                        reportedError = true
                        add(
                            StreamEvent.Error(
                                GenerationError.SseParse(
                                    rawLine = "content_block_stop",
                                    cause = invalidCause,
                                )
                            )
                        )
                    }
                }
                blockTypes.remove(index)
                thinkingSignatures.remove(index)
            }

            "message_delta" -> {
                // stop_reason lives on `delta`, not on `message`. This is the only event that
                // carries it, so getting the location wrong disabled all stop diagnostics.
                (event.delta?.stopReason ?: event.message?.stopReason)
                    ?.takeIf(String::isNotBlank)
                    ?.let { stopReason = it.lowercase() }
                event.usage?.let { usage ->
                // Relay/non-standard endpoints report the real input & cache counts only in
                // this terminal event (message_start carries all-zero placeholders); standard
                // Anthropic reports them in message_start and omits them here. Adopt any value
                // the delta actually carries, else keep the message_start value — correct for both.
                usage.inputTokens?.coerceAtLeast(0)?.let { inputTokens = it }
                usage.cacheCreationInputTokens?.coerceAtLeast(0)?.let { cacheCreationInputTokens = it }
                usage.cacheReadInputTokens?.coerceAtLeast(0)?.let { cacheReadInputTokens = it }
                val uncachedInput = inputTokens?.let { input ->
                    TokenUsage.addCounts(input, cacheCreationInputTokens)
                }
                val totalInput = uncachedInput?.let { uncached ->
                    TokenUsage.addCounts(uncached, cacheReadInputTokens)
                }
                val output = usage.outputTokens?.coerceAtLeast(0)
                val total = when {
                    totalInput != null && output != null ->
                        TokenUsage.addCounts(totalInput, output)
                    totalInput != null -> totalInput
                    else -> output ?: 0
                }
                add(
                    StreamEvent.UsageUpdate(
                        TokenUsage(
                            totalTokenCount = total,
                            inputTokenCount = totalInput,
                            cachedInputTokenCount =
                                cacheReadInputTokens.takeIf { totalInput != null },
                            uncachedInputTokenCount = uncachedInput,
                            outputTokenCount = output,
                        )
                    )
                )
            }
            }

            "message_stop" -> {
                messageStopReceived = true
                // Spec-compliant message_stop is field-less. A relay that attaches the reason
                // here anyway is still honored rather than ignored.
                (event.delta?.stopReason ?: event.message?.stopReason)
                    ?.takeIf(String::isNotBlank)
                    ?.let { stopReason = it.lowercase() }
            }

            "error" -> {
                streamError = event.error.toGenerationError()
            }
        }

        // A relay may send the error payload without the `error` event type, or attach it to an
        // otherwise-unknown event. Capture it wherever it appears rather than discarding it.
        if (streamError == null && event.error != null && event.type != "error") {
            streamError = event.error.toGenerationError()
        }
        if (streamError == null && ProviderRetryPolicy.isFailedToGenerateOutcome(event.outcome)) {
            streamError = GenerationError.Api(
                code = null,
                type = "failed_to_generate",
                message = event.outcome.orEmpty(),
            )
        }
    }

    /**
     * Surface malformed open blocks without turning any unclosed block into an executable call.
     * A name alone does not prove that the streamed JSON arguments were complete.
     */
    fun reportIncompleteBlocks(): List<StreamEvent> = buildList {
        // One attempt owns one terminal diagnostic. Named open blocks are represented by
        // toolCallInFlight in StreamTermination; report at most one additional identity error.
        toolBlocks.toSortedMap().values.firstOrNull { !it.name.matches(safeWireToolName) }?.let {
            reportedError = true
            add(
                StreamEvent.Error(
                    GenerationError.SseParse(
                        rawLine = "stream_end",
                        cause = "Provider ended before the tool name was complete",
                    )
                )
            )
        }
    }

    fun captureParseError(rawLine: String, cause: String) {
        if (streamError == null) {
            streamError = GenerationError.SseParse(rawLine.take(512), cause)
        }
    }

    private fun AnthropicStreamError?.toGenerationError(): GenerationError =
        GenerationError.Api(
            code = null,
            type = this?.type,
            message = this?.message?.takeIf(String::isNotBlank)
                ?: "Provider reported an error in the response stream",
        )

    private fun routeUntypedDelta(
        index: Int,
        delta: AnthropicDelta,
        output: MutableList<StreamEvent>,
    ) {
        when (blockTypes[index]) {
            "tool_use" -> {
                val tool = toolBlocks[index] ?: return
                tool.arguments.append(delta.partialJson)
                output += tool.updateEvent()
            }

            "thinking" -> {
                delta.signature?.takeIf(String::isNotBlank)?.let {
                    thinkingSignatures[index] = it
                }
                (delta.thinking ?: delta.text)?.takeIf(String::isNotEmpty)?.let {
                    output += StreamEvent.ThoughtChunk(
                        thought = it,
                        signature = thinkingSignatures[index],
                    )
                }
            }

            "text" -> delta.text?.takeIf(String::isNotEmpty)?.let {
                output += StreamEvent.TextChunk(it)
            }
        }
    }

    private fun indexForStart(index: Int?): Int {
        val resolved = index ?: syntheticIndex++
        lastBlockIndex = resolved
        return resolved
    }

    private fun indexForContinuation(index: Int?): Int =
        index ?: lastBlockIndex.takeIf { it >= 0 } ?: syntheticIndex++

    private fun ToolBlock.updateEvent() = StreamEvent.ToolCallUpdate(
        streamKey = streamKey,
        id = id,
        name = name,
        arguments = arguments.toString(),
    )

    private fun ToolBlock.completeEvent() = StreamEvent.ToolCallRequest(
        id = id ?: streamKey,
        name = name,
        arguments = arguments.toString().ifBlank { "{}" },
        streamKey = streamKey,
    )

    private fun ToolBlock.invalidCompletionCause(): String? {
        val effectiveId = id ?: streamKey
        if (!effectiveId.matches(safeWireToolCallId)) {
            return "Provider returned an invalid tool call id"
        }
        if (effectiveId in completedToolCallIds) {
            return "Provider returned a duplicate tool call id"
        }
        if (!name.matches(safeWireToolName)) {
            return "Provider ended before the tool name was complete"
        }
        val rawArguments = arguments.toString().ifBlank { "{}" }
        val completeObject = runCatching {
            ROUTER_JSON.parseToJsonElement(rawArguments) is JsonObject
        }.getOrDefault(false)
        return if (completeObject) null
        else "Provider ended before the tool arguments formed a complete JSON object"
    }

    private companion object {
        val ROUTER_JSON = Json { ignoreUnknownKeys = true }
    }
}
