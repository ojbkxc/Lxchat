package com.lxseek.chat.api.compression

import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.util.Constants

/**
 * 输入侧省 token 消息级入口（Headroom 移植层的使用方）。
 *
 * 在 [com.lxseek.chat.api.util.prepareMessages] 的规范化管道中默认启用：
 * 对工具协议行（tool_/result_）里的工具结果做内容感知压缩，
 * 所有 Provider（OpenAI / Gemini / Anthropic / Ollama / Local）自动受益。
 *
 * 边界（对应 Headroom 的 verbatim/exclude 名单思想）：
 * - 普通用户/助手消息的正文不动 —— 那是会话语义，不是机器输出；
 * - 代码块（``` 围栏）不动 —— 代码必须逐字保留；
 * - 工具名 / 参数 / 签名 / call id 不动 —— 那是协议状态；
 * - 最近一轮工具结果不压缩 —— 模型刚拿到、正要基于它推理，压缩反而伤质量。
 *
 * 只读消息并返回副本，失败一律回退原消息（fail-open）。
 */
object InputTokenSaver {

    /** 从倒数第几轮工具结果开始参与压缩；最近一轮保持原样。 */
    private const val RECENT_ROUNDS_EXEMPT = 1

    /** 单条工具结果低于此字符数不处理（与 [ContentRouter.MIN_COMPRESS_LENGTH] 对齐）。 */
    private const val MIN_RESULT_CHARS = ContentRouter.MIN_COMPRESS_LENGTH

    /** Markdown 代码围栏分隔符。 */
    private const val FENCE_DELIMITER = "```"

    /**
     * 压缩消息列表中的历史工具结果。
     *
     * @param messages 已规范化的消息列表。
     * @return 工具结果被压缩后的新列表；无改动时原样返回（同一引用）。
     */
    fun apply(messages: List<ChatMessage>): List<ChatMessage> {
        if (messages.isEmpty()) return messages

        // 找到最后一个 result_ 行的位置：其后的工具结果属于"最近一轮"，豁免。
        val lastResultIndex = messages.indexOfLast {
            it.id.startsWith(Constants.RESULT_MSG_PREFIX)
        }
        if (lastResultIndex < 0) return messages

        // 向前划出豁免区（本轮工具往返的起点）。
        var exemptFrom = lastResultIndex
        while (
            exemptFrom > 0 &&
            !messages[exemptFrom - 1].id.startsWith(Constants.TOOL_MSG_PREFIX) &&
            messages[exemptFrom - 1].id.startsWith(Constants.RESULT_MSG_PREFIX)
        ) {
            exemptFrom--
        }

        var changed = false
        val output = messages.mapIndexed { index, message ->
            if (index >= exemptFrom - RECENT_ROUNDS_EXEMPT) return@mapIndexed message
            val compressed = compressMessage(message) ?: return@mapIndexed message
            changed = true
            compressed
        }
        return if (changed) output else messages
    }

    /** 压缩单条消息里的工具结果文本（segments 与 toolCall 两个载体都处理）。 */
    private fun compressMessage(message: ChatMessage): ChatMessage? {
        if (!message.isToolProtocolMessage()) return null

        var changed = false
        val segments = message.segments?.map { segment ->
            val result = segment.toolResult
            if (result == null || result.length < MIN_RESULT_CHARS) {
                segment
            } else {
                val compressed = compressToolResult(result)
                if (compressed === result || compressed == result) {
                    segment
                } else {
                    changed = true
                    segment.copy(toolResult = compressed)
                }
            }
        }
        val toolCall = message.toolCall?.let { call ->
            if (call.result.length < MIN_RESULT_CHARS) {
                call
            } else {
                val compressed = compressToolResult(call.result)
                if (compressed == call.result) call
                else {
                    changed = true
                    call.copy(result = compressed)
                }
            }
        }
        if (!changed) return null
        return message.copy(segments = segments, toolCall = toolCall)
    }

    /**
     * 压缩一段工具结果：保护代码围栏，其余交给 [ContentRouter]。
     *
     * 围栏边界用 indexOf 循环定位：围栏外散文分段压缩，围栏内文本
     * （含语言标注）原样保留，开、闭分隔符在重建时逐个补回。
     * 旧实现按 split("```") 重建时只补开分隔符，闭合 "```" 全部丢失，
     * 发给模型的代码围栏永不闭合、后续文本被吞进代码块。
     */
    internal fun compressToolResult(result: String): String {
        if (!result.contains(FENCE_DELIMITER)) {
            // 无围栏：整体交给 ContentRouter。
            return ContentRouter.compress(result)
        }
        val rebuilt = StringBuilder()
        var cursor = 0
        var insideFence = false
        while (true) {
            val fence = result.indexOf(FENCE_DELIMITER, cursor)
            if (!insideFence) {
                if (fence < 0) {
                    // 收尾：最后一段围栏外散文压缩后结束。
                    rebuilt.append(ContentRouter.compress(result.substring(cursor)))
                    break
                }
                // 围栏开始：压缩其前的散文，原样补回开分隔符。
                rebuilt.append(ContentRouter.compress(result.substring(cursor, fence)))
                rebuilt.append(FENCE_DELIMITER)
                insideFence = true
            } else {
                if (fence < 0) {
                    // 未闭合围栏：剩余文本视作围栏内容原样保留，不丢文本也不报错。
                    rebuilt.append(result.substring(cursor))
                    break
                }
                // 围栏闭合：围栏内文本原样保留，原样补回闭分隔符。
                rebuilt.append(result.substring(cursor, fence))
                rebuilt.append(FENCE_DELIMITER)
                insideFence = false
            }
            cursor = fence + FENCE_DELIMITER.length
        }
        val joined = rebuilt.toString()
        return if (joined.length < result.length) joined else result
    }

    private fun ChatMessage.isToolProtocolMessage(): Boolean =
        id.startsWith(Constants.TOOL_MSG_PREFIX) || id.startsWith(Constants.RESULT_MSG_PREFIX)
}
