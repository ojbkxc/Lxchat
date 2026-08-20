package com.lxseek.chat.viewmodel

import android.content.Context
import com.lxseek.chat.data.local.MessageEntity
import com.lxseek.chat.model.AttachmentMeta
import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.MessageSegment
import com.lxseek.chat.model.TokenUsage
import com.lxseek.chat.model.ToolCallData
import com.lxseek.chat.util.Constants
import com.lxseek.chat.util.SearchResultFormatter
import kotlinx.serialization.json.Json

/**
 * The single projection from a durable message row into UI state.
 *
 * Room observations and controller-owned atomic graph commits must use the same projection.
 * Otherwise a branch mutation can temporarily replace a fully decoded message with a partial
 * copy and leave persisted thought/tool segments invisible until the conversation is reopened.
 */
internal fun MessageEntity.toUiChatMessage(context: Context): ChatMessage =
    toUiChatMessage { value -> SearchResultFormatter.format(value, context) }

internal fun MessageEntity.toUiChatMessage(
    formatText: (String) -> String,
): ChatMessage {
    val isSynthetic =
        id.startsWith(Constants.TOOL_MSG_PREFIX) ||
            id.startsWith(Constants.RESULT_MSG_PREFIX)
    // Protocol rows only participate in the branch walk. Provider history is built from
    // MessageEntity snapshots, so copying their potentially huge results into UI state only
    // increases allocation and GC pressure.
    val decodedSegments = if (isSynthetic) {
        null
    } else {
        toolCallJson?.let { raw ->
            runCatching {
                Json.decodeFromString<List<MessageSegment>>(raw)
            }.getOrNull()
        }
    }
    return ChatMessage(
        id = id,
        parentId = parentId,
        text = if (isSynthetic) "" else formatText(text),
        images = if (isSynthetic) emptyList() else images,
        thoughts = if (isSynthetic) null else thoughts,
        thoughtTitle = if (isSynthetic) null else thoughtTitle,
        tokenCount = if (isSynthetic) 0 else tokenCount,
        tokenUsage = if (isSynthetic) {
            null
        } else {
            TokenUsage.fromPersisted(
                totalTokenCount = tokenCount,
                inputTokenCount = inputTokenCount,
                cachedInputTokenCount = cachedInputTokenCount,
                uncachedInputTokenCount = uncachedInputTokenCount,
                outputTokenCount = outputTokenCount,
                reasoningTokenCount = reasoningTokenCount,
            )
        },
        status = status,
        participant = participant,
        timestamp = timestamp,
        thoughtTimeMs = if (isSynthetic) null else thoughtTimeMs,
        modelName = modelName,
        segments = decodedSegments
            ?: thoughts
                ?.takeIf { thought -> !isSynthetic && thought.isNotBlank() }
                ?.let { thought ->
                    listOf(
                        MessageSegment(
                            type = "thought",
                            content = thought,
                        )
                    )
                },
        toolCall = decodedSegments
            ?.lastOrNull { segment -> segment.type == "tool" }
            ?.let { segment ->
                ToolCallData(
                    toolName = segment.toolName.orEmpty(),
                    arguments = segment.toolArgs ?: "{}",
                    result = formatText(segment.toolResult.orEmpty()),
                    signature = segment.signature,
                    toolCallId = segment.toolCallId,
                    resultImages = segment.toolImages,
                    displayName = segment.toolDisplayName,
                    resultText = segment.toolResultText,
                    structuredResult = segment.toolStructuredResult,
                )
            },
        attachmentMeta = if (isSynthetic) {
            null
        } else {
            attachmentMeta?.let { raw ->
                runCatching { Json.decodeFromString<AttachmentMeta>(raw) }.getOrNull()
            }
        },
        runId = runId,
        runSequence = runSequence,
        consumedAtPass = consumedAtPass,
    )
}
