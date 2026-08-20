package com.lxseek.chat.viewmodel

import com.lxseek.chat.api.StreamEvent
import com.lxseek.chat.model.MessageSegment
import com.lxseek.chat.model.RunEffect
import com.lxseek.chat.model.RunEffectIdentity
import com.lxseek.chat.model.ToolCallData
import com.lxseek.chat.model.ToolExecutionStates
import com.lxseek.chat.tool.ToolExecutionEvent
import com.lxseek.chat.tool.ToolExecutionResult
import com.lxseek.chat.util.Constants

private const val TOOL_PROGRESS_UI_UPDATE_INTERVAL_MS = 50L

internal class GenerationToolOverlay(
    private val presentation: GenerationToolPresentationSource,
    private val providerName: String,
) {
    private val segments = mutableListOf(MessageSegment(type = "answer"))
    private val streamIndices = mutableMapOf<String, Int>()

    val size: Int
        get() = segments.size

    fun snapshot(): List<MessageSegment> = segments.toList()

    fun replaceAll(replacement: List<MessageSegment>) {
        segments.clear()
        segments.addAll(replacement)
        streamIndices.clear()
    }

    fun prependAll(prefix: List<MessageSegment>) {
        if (prefix.isEmpty()) return
        segments.addAll(0, prefix)
        streamIndices.entries.forEach { entry -> entry.setValue(entry.value + prefix.size) }
    }

    fun append(segment: MessageSegment) = appendMergedSegment(segments, segment)

    fun hasStream(streamKey: String): Boolean = streamKey in streamIndices

    fun upsert(
        streamKey: String,
        toolCallId: String?,
        name: String,
        arguments: String,
        signature: String?,
    ): Boolean {
        val existingIndex = streamIndices[streamKey]
        if (existingIndex != null) {
            val existing = segments[existingIndex]
            val resolvedName = name.ifBlank { existing.toolName.orEmpty() }
            val metadata = presentation.presentationMetadata(resolvedName)
            segments[existingIndex] = existing.copy(
                toolName = resolvedName.ifBlank { existing.toolName },
                toolArgs = arguments,
                toolCallId = toolCallId ?: existing.toolCallId ?: streamKey,
                signature = signature ?: existing.signature,
                signatureProvider = providerName.takeIf {
                    signature != null || existing.signature != null
                },
                toolState = ToolExecutionStates.CALLING,
                toolTarget = metadata?.target ?: existing.toolTarget,
                toolDisplayName = metadata?.displayName ?: existing.toolDisplayName,
            )
            return false
        }

        val index = segments.size
        val metadata = presentation.presentationMetadata(name)
        segments += MessageSegment(
            type = "tool",
            toolName = name.ifBlank { null },
            toolArgs = arguments,
            toolResult = null,
            toolCallId = toolCallId ?: streamKey,
            signature = signature,
            signatureProvider = providerName.takeIf { signature != null },
            toolState = ToolExecutionStates.CALLING,
            toolTarget = metadata?.target,
            toolDisplayName = metadata?.displayName,
        )
        streamIndices[streamKey] = index
        return true
    }

    fun start(call: StreamEvent.ToolCallRequest) {
        val index = checkNotNull(streamIndices[call.streamKey]) {
            "Missing live segment for tool call ${call.streamKey}"
        }
        val current = segments[index]
        val metadata = presentation.presentationMetadata(call.name)
        segments[index] = current.copy(
            toolName = call.name,
            toolArgs = call.arguments,
            toolCallId = call.id,
            signature = call.signature,
            signatureProvider = providerName.takeIf { call.signature != null },
            toolState = ToolExecutionStates.RUNNING,
            toolTarget = metadata?.target ?: current.toolTarget,
            toolDisplayName = metadata?.displayName ?: current.toolDisplayName,
        )
    }

    fun applyProgress(callId: String, event: ToolExecutionEvent) {
        val index = segments.indexOfLast { it.toolCallId == callId }
        if (index < 0) return
        val current = segments[index]
        segments[index] = when (event) {
            is ToolExecutionEvent.OutputDelta -> current.copy(
                toolState = ToolExecutionStates.RUNNING,
                toolProgress = appendBoundedToolOutput(current.toolProgress, event.text),
            )
            is ToolExecutionEvent.TargetResolved -> current.copy(toolTarget = event.target)
            is ToolExecutionEvent.Progress -> current.copy(toolState = ToolExecutionStates.RUNNING)
            is ToolExecutionEvent.Completed -> current
        }
    }

    fun complete(call: StreamEvent.ToolCallRequest, result: ToolExecutionResult): CompletedToolCall {
        val index = checkNotNull(streamIndices[call.streamKey]) {
            "Missing live segment for tool call ${call.streamKey}"
        }
        val clipped = result.text.take(Constants.MAX_TOOL_RESULT_LENGTH)
        val displayText = result.displayText?.take(Constants.MAX_TOOL_RESULT_LENGTH)
        val structuredResult = result.structuredContent?.take(Constants.MAX_TOOL_RESULT_LENGTH)
        val completed = segments[index].copy(
            toolResult = clipped,
            toolResultText = displayText,
            toolStructuredResult = structuredResult,
            toolState = if (result.isError) ToolExecutionStates.FAILED else finalToolState(result.text),
            toolImages = result.images,
        )
        segments[index] = completed
        return CompletedToolCall(
            segment = completed,
            data = ToolCallData(
                toolName = call.name,
                arguments = call.arguments,
                result = clipped,
                signature = call.signature,
                toolCallId = call.id,
                resultImages = result.images,
                displayName = completed.toolDisplayName,
                resultText = displayText,
                structuredResult = structuredResult,
            ),
        )
    }

    fun failIncompleteStreams(completedStreamKeys: Set<String>) {
        streamIndices.forEach { (streamKey, index) ->
            val segment = segments[index]
            if (streamKey !in completedStreamKeys && segment.toolResult == null) {
                segments[index] = segment.copy(toolState = ToolExecutionStates.FAILED)
            }
        }
    }

    fun stopIncompleteTools() {
        segments.indices.forEach { index ->
            val segment = segments[index]
            if (segment.type == "tool" && segment.toolResult == null) {
                segments[index] = segment.copy(toolState = ToolExecutionStates.STOPPED)
            }
        }
    }
}

internal data class CompletedToolCall(
    val segment: MessageSegment,
    val data: ToolCallData,
)

internal data class AuthorizedToolBatchRequest(
    val effect: RunEffect.ExecuteToolBatch,
    val calls: List<StreamEvent.ToolCallRequest>,
    val context: GenerationContext,
    val conversationId: String,
) {
    init {
        require(calls.isNotEmpty())
    }
}

internal data class AuthorizedToolBatchOutcome(
    val identity: RunEffectIdentity,
    val calls: List<ToolCallData>,
    val segments: List<MessageSegment>,
    val generatedImages: List<String>,
)

internal data class ToolBatchProgressCallbacks(
    val publish: suspend (forceCheckpoint: Boolean) -> Unit,
    val onPublishedAt: (Long) -> Unit,
)

/** Executes one already-authorized tool batch without committing or continuing the Run. */
internal class GenerationToolBatchEffectExecutor(
    private val tools: GenerationToolExecutor,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    suspend fun execute(
        request: AuthorizedToolBatchRequest,
        overlay: GenerationToolOverlay,
        callbacks: ToolBatchProgressCallbacks,
    ): AuthorizedToolBatchOutcome {
        val results = mutableListOf<ToolCallData>()
        val completedSegments = mutableListOf<MessageSegment>()
        val generatedImages = mutableListOf<String>()

        request.calls.forEach { call ->
            overlay.start(call)
            callbacks.publish(true)
            callbacks.onPublishedAt(nowMs())

            var lastToolUiEmitMs = 0L
            val executed = tools.execute(
                AuthorizedToolCall(
                    batchIdentity = request.effect.identity,
                    callId = call.id,
                    name = call.name,
                    arguments = call.arguments,
                    context = request.context,
                ),
            ) { event ->
                if (event !is ToolExecutionEvent.Completed) {
                    overlay.applyProgress(call.id, event)
                    val now = nowMs()
                    if (now - lastToolUiEmitMs >= TOOL_PROGRESS_UI_UPDATE_INTERVAL_MS) {
                        callbacks.publish(false)
                        callbacks.onPublishedAt(now)
                        lastToolUiEmitMs = now
                    }
                }
            }
            check(executed.batchIdentity == request.effect.identity)
            check(executed.callId == call.id)
            generatedImages += tools.drainGeneratedImages(request.conversationId)
            val completed = overlay.complete(call, executed.result)
            completedSegments += completed.segment
            results += completed.data
            callbacks.publish(false)
            callbacks.onPublishedAt(nowMs())
        }

        return AuthorizedToolBatchOutcome(
            identity = request.effect.identity,
            calls = results,
            segments = completedSegments,
            generatedImages = generatedImages,
        )
    }
}
