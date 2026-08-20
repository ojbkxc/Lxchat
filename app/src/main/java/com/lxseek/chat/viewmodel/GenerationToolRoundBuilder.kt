package com.lxseek.chat.viewmodel

import com.lxseek.chat.data.local.MessageEntity
import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.MessagePersistenceGuard
import com.lxseek.chat.model.MessageSegment
import com.lxseek.chat.model.MessageStatus
import com.lxseek.chat.model.Participant
import com.lxseek.chat.model.ToolCallData
import com.lxseek.chat.util.Constants
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

internal data class PersistableToolRound(
    val pathMessages: List<ChatMessage>,
    val entities: List<MessageEntity>,
    val lastResultId: String,
)

/** Builds one immutable Provider-path/Room representation of a completed tool round. */
internal class GenerationToolRoundBuilder(
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    fun build(
        previousMessageId: String?,
        conversationId: String,
        runId: String,
        modelName: String,
        providerName: String,
        calls: List<ToolCallData>,
        completedSegments: List<MessageSegment>,
    ): PersistableToolRound {
        require(calls.isNotEmpty())
        val toolMessageId = "${Constants.TOOL_MSG_PREFIX}${newId()}"
        val toolSegments = completedSegments.ifEmpty { null }
        val allSegments = toolSegments ?: calls.map { it.toSegment(providerName) }
        // The aggregate shares one Room column; repeatedly halve large results until the encoded
        // representation stays under the existing CursorWindow guard.
        val boundedSegmentsJson = MessagePersistenceGuard.encodeSegmentsBounded(allSegments)
        val resultMessages = calls.map { call ->
            val resultId = "${Constants.RESULT_MSG_PREFIX}${newId()}"
            resultId to ChatMessage(
                id = resultId,
                parentId = toolMessageId,
                text = call.result,
                images = call.resultImages.map { it.path },
                participant = Participant.USER,
                status = MessageStatus.SUCCESS,
                toolCall = call,
                runId = runId,
            )
        }
        val toolPathMessage = ChatMessage(
            id = toolMessageId,
            parentId = previousMessageId,
            text = "",
            participant = Participant.MODEL,
            status = MessageStatus.SUCCESS,
            toolCall = calls.first(),
            segments = toolSegments,
            runId = runId,
        )
        val timestamp = nowMs()
        val entities = buildList {
            add(
                MessageEntity(
                    id = toolMessageId,
                    conversationId = conversationId,
                    parentId = previousMessageId,
                    text = "",
                    thoughts = null,
                    status = MessageStatus.SUCCESS,
                    participant = Participant.MODEL,
                    timestamp = timestamp,
                    modelName = modelName,
                    toolCallJson = boundedSegmentsJson,
                    runId = runId,
                ),
            )
            resultMessages.forEachIndexed { index, (resultId, _) ->
                val call = calls[index]
                add(
                    MessageEntity(
                        id = resultId,
                        conversationId = conversationId,
                        parentId = toolMessageId,
                        text = call.result,
                        thoughts = null,
                        status = MessageStatus.SUCCESS,
                        images = call.resultImages.map { it.path },
                        participant = Participant.USER,
                        timestamp = timestamp + index + 1,
                        modelName = modelName,
                        runId = runId,
                        toolCallJson = Json.encodeToString(listOf(call.toSegment(providerName))),
                    ),
                )
            }
        }
        return PersistableToolRound(
            pathMessages = listOf(toolPathMessage) + resultMessages.map { it.second },
            entities = entities,
            lastResultId = resultMessages.last().first,
        )
    }

    private fun ToolCallData.toSegment(providerName: String) = MessageSegment(
        type = "tool",
        toolName = toolName,
        toolArgs = arguments,
        toolResult = result,
        signature = signature,
        signatureProvider = providerName.takeIf { signature != null },
        toolCallId = toolCallId,
        toolDisplayName = displayName,
        toolResultText = resultText,
        toolStructuredResult = structuredResult,
        toolImages = resultImages,
    )
}
