package com.lxseek.chat.api.util

import com.lxseek.chat.util.DebugLog
import com.lxseek.chat.api.OpenAiContentPart
import com.lxseek.chat.api.OpenAiImageUrl
import com.lxseek.chat.api.OpenAiMessage
import com.lxseek.chat.api.OpenAiRequestFunction
import com.lxseek.chat.api.OpenAiRequestToolCall
import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.Participant
import com.lxseek.chat.util.Constants
import java.io.File
import java.security.MessageDigest

fun buildToolCallId(toolName: String, arguments: String, prefix: String = Constants.TOOL_CALL_ID_PREFIX): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val input = "$toolName:$arguments"
    val hash = digest.digest(input.toByteArray())
    val shortHash = hash.take(8).joinToString("") { "%02x".format(it) }
    val safeName = toolName
        .trim()
        .replace(Regex("[^A-Za-z0-9_-]+"), "_")
        .trim('_')
        .take(32)
        .ifBlank { "tool" }
    return "$prefix${safeName}_$shortHash"
}

/** Maps an image file path to its MIME type. Providers reject a mislabeled payload
 *  (e.g. a webp sent as image/jpeg), so cover every format the pickers accept. */
fun imageMimeType(imagePath: String): String = when {
    imagePath.endsWith(".png", ignoreCase = true) -> "image/png"
    imagePath.endsWith(".webp", ignoreCase = true) -> "image/webp"
    imagePath.endsWith(".gif", ignoreCase = true) -> "image/gif"
    else -> "image/jpeg"
}

fun encodeImageToBase64(imagePath: String): Pair<String, String>? {
    return try {
        val file = File(imagePath)
        if (!file.exists()) return null
        val bytes = file.readBytes()
        val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        imageMimeType(imagePath) to base64
    } catch (e: Exception) {
        DebugLog.e(
            "LxChatAPI",
            "Failed to encode image exception=${e.javaClass.simpleName}",
        )
        null
    }
}

fun convertToOpenAiMessages(
    messages: List<ChatMessage>,
    systemPrompt: String? = null,
    includeImages: Boolean = true
): List<OpenAiMessage> {
    val apiMessages = mutableListOf<OpenAiMessage>()

    if (!systemPrompt.isNullOrBlank()) {
        apiMessages.add(
            OpenAiMessage(
                role = "system",
                content = listOf(OpenAiContentPart(type = "text", text = systemPrompt))
            )
        )
    }

    apiMessages.addAll(messages.flatMap { msg ->
        val entries = mutableListOf<OpenAiMessage>()

        // tool_ messages: assistant turn with tool_calls only
        // (tool results come from the following result_ messages)
        if (msg.id.startsWith(Constants.TOOL_MSG_PREFIX)) {
            val toolSegs = msg.segments?.filter { it.type == "tool" }
            val thoughtContent = msg.segments?.lastOrNull { it.type == "thought" }?.content
            if (!toolSegs.isNullOrEmpty()) {
                val toolCalls = toolSegs.map { seg ->
                    val tid = seg.toolCallId ?: buildToolCallId(seg.toolName ?: "", seg.toolArgs ?: "{}")
                    OpenAiRequestToolCall(
                        id = tid,
                        function = OpenAiRequestFunction(name = seg.toolName ?: "", arguments = seg.toolArgs ?: "{}")
                    )
                }
                entries.add(OpenAiMessage(
                    role = "assistant",
                    content = null,
                    toolCalls = toolCalls,
                    reasoningContent = thoughtContent?.ifEmpty { null }
                ))
            } else if (msg.toolCall != null) {
                val tc = msg.toolCall!!
                val toolId = tc.toolCallId ?: buildToolCallId(tc.toolName, tc.arguments)
                entries.add(OpenAiMessage(
                    role = "assistant",
                    content = null,
                    toolCalls = listOf(OpenAiRequestToolCall(
                        id = toolId,
                        function = OpenAiRequestFunction(name = tc.toolName, arguments = tc.arguments)
                    )),
                    reasoningContent = thoughtContent?.ifEmpty { null }
                ))
            }
            return@flatMap entries
        }

        // result_ messages carry the tool result(s)
        if (msg.id.startsWith(Constants.RESULT_MSG_PREFIX)) {
            val toolSegs = msg.segments?.filter { it.type == "tool" }
            if (!toolSegs.isNullOrEmpty()) {
                for (seg in toolSegs) {
                    val toolId = seg.toolCallId ?: buildToolCallId(seg.toolName ?: "", seg.toolArgs ?: "{}")
                    entries.add(OpenAiMessage(
                        role = "tool",
                        content = listOf(OpenAiContentPart(type = "text", text = seg.toolResult ?: "")),
                        toolCallId = toolId
                    ))
                }
            } else if (msg.toolCall != null) {
                val tc = msg.toolCall!!
                val toolId = tc.toolCallId ?: buildToolCallId(tc.toolName, tc.arguments)
                entries.add(OpenAiMessage(
                    role = "tool",
                    content = listOf(OpenAiContentPart(type = "text", text = tc.result)),
                    toolCallId = toolId
                ))
            }
            return@flatMap entries
        }

        // Normal message: text + images
        val parts = mutableListOf<OpenAiContentPart>()
        if (msg.text.isNotEmpty()) {
            parts.add(OpenAiContentPart(type = "text", text = msg.text))
        }

        if (includeImages && msg.participant == Participant.USER) {
            for (imagePath in msg.images) {
                val encoded = encodeImageToBase64(imagePath)
                if (encoded != null) {
                    val (mimeType, base64) = encoded
                    parts.add(
                        OpenAiContentPart(
                            type = "image_url",
                            imageUrl = OpenAiImageUrl(url = "data:$mimeType;base64,$base64")
                        )
                    )
                }
            }
        }

        if (parts.isEmpty()) {
            parts.add(OpenAiContentPart(type = "text", text = "[Attachment unavailable]"))
        }

        entries.add(OpenAiMessage(
            role = if (msg.participant == Participant.USER) "user" else "assistant",
            content = parts
        ))
        entries
    })

    return apiMessages
}

fun limitContext(messages: List<ChatMessage>, contextTokenBudget: Int): List<ChatMessage> {
    if (messages.isEmpty()) return emptyList()

    // A tool call and all of its results are one protocol unit. Truncating the flat list can leave
    // either an orphan result or an unanswered assistant tool call, so window complete units only.
    val units = mutableListOf<List<ChatMessage>>()
    var index = 0
    while (index < messages.size) {
        val message = messages[index]
        if (message.id.startsWith(Constants.TOOL_MSG_PREFIX)) {
            val round = mutableListOf(message)
            index++
            while (
                index < messages.size &&
                messages[index].id.startsWith(Constants.RESULT_MSG_PREFIX)
            ) {
                round += messages[index++]
            }
            units += round
        } else {
            units += listOf(message)
            index++
        }
    }

    val selected = ArrayDeque<List<ChatMessage>>()
    var estimatedTokens = 0L
    var hasNormalUserAnchor = false
    val tokenBudget = contextTokenBudget.coerceAtLeast(1).toLong()
    for (unit in units.asReversed()) {
        val unitCost = ContextTokenEstimator.estimate(unit).toLong()
        if (
            selected.isNotEmpty() &&
            hasNormalUserAnchor &&
            estimatedTokens + unitCost > tokenBudget
        ) break
        selected.addFirst(unit)
        estimatedTokens = (estimatedTokens + unitCost).coerceAtMost(Int.MAX_VALUE.toLong())
        if (unit.any {
                it.participant == Participant.USER && !it.isToolProtocolMessage()
            }
        ) hasNormalUserAnchor = true
        // Always retain a legal user anchor and at least the newest complete protocol unit, even
        // when that single input already exceeds the configured estimate.
        if (hasNormalUserAnchor && estimatedTokens >= tokenBudget) break
    }

    val flattened = selected.flatten()
    // All supported chat protocols accept a user-led suffix. Starting at an assistant/tool turn
    // after truncation is ambiguous and is a common source of provider-side 400 responses.
    val firstNormalUser = flattened.indexOfFirst {
        it.participant == Participant.USER && !it.isToolProtocolMessage()
    }
    return if (firstNormalUser >= 0) flattened.drop(firstNormalUser) else emptyList()
}
