package com.lxseek.chat.viewmodel

import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.MessagePersistenceGuard
import com.lxseek.chat.model.MessageSegment
import com.lxseek.chat.model.MessageStatus
import com.lxseek.chat.model.Participant
import com.lxseek.chat.model.TokenUsage

internal class GenerationThoughtTiming(
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private var cumulativeDurationMs = 0L
    private var currentStartedAtMs: Long? = null

    var currentDurationMs: Long = 0L
        private set
    var totalDurationMs: Long? = null
        private set

    fun ensureStarted() {
        if (currentStartedAtMs == null) currentStartedAtMs = nowMs()
    }

    fun liveDurationMs(): Long? {
        val liveElapsed = currentStartedAtMs?.let { nowMs() - it } ?: 0L
        return (currentDurationMs + liveElapsed).takeIf { it > 0L }
    }

    fun finishCurrent() {
        val startedAt = currentStartedAtMs ?: return
        val elapsed = nowMs() - startedAt
        if (elapsed > 0L) {
            cumulativeDurationMs += elapsed
            currentDurationMs += elapsed
            totalDurationMs = cumulativeDurationMs
        }
        currentStartedAtMs = null
    }

    fun resetCurrentDuration() {
        currentDurationMs = 0L
    }

    fun adoptTotalDuration(durationMs: Long?) {
        totalDurationMs = durationMs
    }
}

internal fun appendMergedSegment(
    target: MutableList<MessageSegment>,
    segment: MessageSegment,
) {
    val last = target.lastOrNull()
    val canMerge = last != null &&
        last.type == segment.type &&
        (
            segment.type == "answer" ||
                (
                    segment.type == "thought" &&
                        last.signature == null &&
                        segment.signature == null
                    )
            )
    if (canMerge) {
        target[target.lastIndex] = last.copy(
            content = last.content + segment.content,
            signature = segment.signature ?: last.signature,
            signatureProvider = segment.signatureProvider ?: last.signatureProvider,
            durationMs = mergeDurationMs(last.durationMs, segment.durationMs),
        )
    } else {
        target.add(segment)
    }
}

private fun mergeDurationMs(first: Long?, second: Long?): Long? {
    val merged = (first ?: 0L) + (second ?: 0L)
    return merged.takeIf { it > 0L }
}

internal fun buildLiveSegments(
    flushed: List<MessageSegment>,
    answer: CharSequence,
    thought: CharSequence,
    signature: String? = null,
    signatureProvider: String? = null,
    thoughtDurationMs: Long? = null,
): List<MessageSegment>? {
    val result = flushed.toMutableList()
    if (answer.isNotEmpty()) {
        appendMergedSegment(result, MessageSegment(type = "answer", content = answer.toString()))
    }
    if (thought.isNotEmpty()) {
        appendMergedSegment(
            result,
            MessageSegment(
                type = "thought",
                content = thought.toString(),
                signature = signature,
                signatureProvider = signatureProvider,
                durationMs = thoughtDurationMs,
            ),
        )
    }
    return result.ifEmpty { null }
}

internal data class GenerationFinalSnapshot(
    val messageId: String,
    val parentId: String?,
    val text: String,
    val images: List<String>,
    val thoughts: String,
    val thoughtTitle: String?,
    val tokenCount: Int,
    val tokenUsage: TokenUsage?,
    val status: MessageStatus,
    val timestamp: Long,
    val thoughtTimeMs: Long?,
    val modelName: String,
    val flushedSegments: List<MessageSegment>,
    val answerBuffer: String,
    val thoughtBuffer: String,
    val thoughtSignature: String?,
    val thoughtSignatureProvider: String?,
    val thoughtDurationMs: Long?,
    val runId: String,
    val runSequence: Long,
)

internal fun GenerationFinalSnapshot.toMessage(): ChatMessage = ChatMessage(
    id = messageId,
    parentId = parentId,
    text = MessagePersistenceGuard.clipText(text),
    images = images,
    thoughts = thoughts.ifBlank { null },
    thoughtTitle = thoughtTitle,
    tokenCount = tokenCount,
    tokenUsage = tokenUsage,
    status = status,
    participant = Participant.MODEL,
    timestamp = timestamp,
    thoughtTimeMs = thoughtTimeMs,
    modelName = modelName,
    segments = buildLiveSegments(
        flushedSegments,
        answerBuffer,
        thoughtBuffer,
        thoughtSignature,
        thoughtSignatureProvider,
        thoughtDurationMs,
    ) ?: flushedSegments.ifEmpty { null },
    runId = runId,
    runSequence = runSequence,
)
