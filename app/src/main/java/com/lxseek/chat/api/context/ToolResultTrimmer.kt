package com.lxseek.chat.api.context

import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.MessageSegment
import com.lxseek.chat.model.ToolCallData
import com.lxseek.chat.util.Constants

/**
 * 第2层防护：工具结果裁剪器。
 *
 * 设计意图：
 * - 灵感来自 HermesApp `AIMessageManager.summarizeMemory` 中的 `condenseHeadTail` 函数。
 *   HermesApp 对工具结果预览使用 `headChars=140, tailChars=56`，对工具参数使用
 *   `headChars=72, tailChars=32`，对用户消息使用 `headChars=240, tailChars=96`。
 * - 在 Lxchat 中将其前置为"发送前裁剪"：对超长工具结果保留头部和尾部，中间用省略号替代，
 *   避免单条工具结果（如 shell 输出、网页抓取）撑爆上下文窗口。
 * - 纯函数实现，只读消息并返回新副本，不修改原消息。
 *
 * 规则：
 * 1. 仅裁剪工具协议行（tool_/result_）中的 `toolResult` / `toolResultText` / `ToolCallData.result`；
 * 2. 文本长度 <= [maxResultChars] 时原样保留；
 * 3. 超长时保留前 [headChars] 和后 [tailChars] 字符，中间用 `[...已省略 N 字符...]` 替代；
 * 4. [maxResultChars] <= 0 时视为关闭本层防护。
 */
object ToolResultTrimmer {

    /** 默认触发裁剪的工具结果最大字符数。约 8K 字符，避免单条结果撑爆上下文。 */
    const val DEFAULT_MAX_RESULT_CHARS = 8_192

    /** 默认保留的头部字符数。 */
    const val DEFAULT_HEAD_CHARS = 1_024

    /** 默认保留的尾部字符数。 */
    const val DEFAULT_TAIL_CHARS = 512

    /**
     * 裁剪消息列表中所有超长工具结果。
     *
     * @param messages 待处理的消息列表。
     * @param maxResultChars 触发裁剪的阈值；<= 0 时直接返回原列表。
     * @param headChars 保留的头部字符数。
     * @param tailChars 保留的尾部字符数。
     * @return 裁剪后的新消息列表（未修改的原消息保持不变）。
     */
    fun trim(
        messages: List<ChatMessage>,
        maxResultChars: Int = DEFAULT_MAX_RESULT_CHARS,
        headChars: Int = DEFAULT_HEAD_CHARS,
        tailChars: Int = DEFAULT_TAIL_CHARS,
    ): List<ChatMessage> {
        if (maxResultChars <= 0 || messages.isEmpty()) return messages
        return messages.map { msg -> trimMessage(msg, maxResultChars, headChars, tailChars) }
    }

    /** 对单条消息进行工具结果裁剪。 */
    private fun trimMessage(
        message: ChatMessage,
        maxResultChars: Int,
        headChars: Int,
        tailChars: Int,
    ): ChatMessage {
        if (!message.isToolProtocolMessage()) return message

        var changed = false

        // 1. 裁剪 segments 中的工具结果
        val trimmedSegments = message.segments?.map { segment ->
            val trimmedResult = segment.toolResult?.let { condenseHeadTail(it, maxResultChars, headChars, tailChars) }
            val trimmedResultText = segment.toolResultText?.let { condenseHeadTail(it, maxResultChars, headChars, tailChars) }
            if (trimmedResult != null && trimmedResult != segment.toolResult) {
                changed = true
                segment.copy(toolResult = trimmedResult, toolResultText = trimmedResultText)
            } else if (trimmedResultText != null && trimmedResultText != segment.toolResultText) {
                changed = true
                segment.copy(toolResultText = trimmedResultText)
            } else {
                segment
            }
        }

        // 2. 裁剪 toolCall 中的结果
        val trimmedToolCall = message.toolCall?.let { call ->
            val trimmedResult = condenseHeadTail(call.result, maxResultChars, headChars, tailChars)
            if (trimmedResult != call.result) {
                changed = true
                call.copy(result = trimmedResult)
            } else {
                call
            }
        }

        return if (changed) {
            message.copy(segments = trimmedSegments, toolCall = trimmedToolCall)
        } else {
            message
        }
    }

    /**
     * 头尾保留裁剪。
     *
     * - 长度 <= [maxChars] 时原样返回；
     * - 否则保留前 [headChars] 和后 [tailChars]，中间插入省略标记。
     */
    internal fun condenseHeadTail(
        text: String,
        maxChars: Int,
        headChars: Int,
        tailChars: Int,
    ): String {
        if (text.length <= maxChars) return text
        val head = headChars.coerceAtLeast(0)
        val tail = tailChars.coerceAtLeast(0)
        if (head == 0 && tail == 0) return "[...已省略 ${text.length} 字符...]"
        val omitted = text.length - head - tail
        if (omitted <= 0) return text
        val headPart = if (head > 0) text.take(head) else ""
        val tailPart = if (tail > 0) text.takeLast(tail) else ""
        return "$headPart\n[...已省略 $omitted 字符...]\n$tailPart"
    }

    /** 是否为工具协议行（tool_ / result_）。 */
    private fun ChatMessage.isToolProtocolMessage(): Boolean =
        id.startsWith(Constants.TOOL_MSG_PREFIX) || id.startsWith(Constants.RESULT_MSG_PREFIX)
}