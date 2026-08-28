package com.lxseek.chat.api.context

import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.Participant
import com.lxseek.chat.util.Constants

/**
 * 选择性保留策略：标记重要消息，摘要时跳过这些消息。
 *
 * 设计意图：
 * - 当前 [AutoSummarizer] 的摘要策略会无差别地摘要前缀中的所有消息，但有些消息承载了
 *   不可丢失的语义信息（工具调用结果、用户决策、代码块、已有摘要），把它们摘要掉会
 *   导致模型丢失关键上下文。
 * - 本对象负责判定哪些消息"重要"（必须原样保留），并提供分离/重组能力，使摘要只作用
 *   于"可摘要"的普通对话消息。
 *
 * 重要消息判定规则（满足任一即视为重要）：
 * 1. 工具协议消息：id 以 `tool_` 或 `result_` 开头（[ChatMessage.isToolProtocolMessage]）；
 * 2. 携带工具调用的消息：`toolCall != null` 或 segments 中存在 type == "tool" 的段；
 * 3. 用户决策消息：participant == USER 且 text 含决策关键词
 *    （决定/选择/确认/同意/拒绝/decide/choose/confirm）；
 * 4. 含代码块的消息：text 包含 ``` 标记；
 * 5. 已有摘要消息：id 以 `compact_` 开头（[ChatMessage.isContextCompact]），
 *    这类消息本身就是上一轮摘要的结果，再次摘要会丢失累积的摘要语义。
 *
 * 注意：Lxchat 的 [Participant] 枚举只有 USER/MODEL/ERROR，没有独立的 TOOL/SYSTEM 角色，
 * 工具消息通过 id 前缀和 segments 区分，system 语义通过 compact 摘要边界体现。
 */
object SelectiveRetention {

    /** 用户消息中的决策关键词（中英双语）。 */
    private val DECISION_KEYWORDS = arrayOf(
        "决定", "选择", "确认", "同意", "拒绝",
        "decide", "choose", "confirm",
    )

    /** 代码块标记。 */
    private const val CODE_FENCE = "```"

    /**
     * 判定单条消息是否"重要"（摘要时必须原样保留）。
     *
     * @param message 待判定的消息。
     * @return true 表示该消息重要，不应被摘要。
     */
    fun isImportant(message: ChatMessage): Boolean {
        // 规则1：工具协议消息（tool_/result_ 前缀）
        if (message.isToolProtocolMessage()) return true

        // 规则5：已有摘要消息（compact_ 前缀），再次摘要会丢失累积摘要语义
        if (message.isContextCompact()) return true

        // 规则2：携带工具调用（toolCall 非空或 segments 含 tool 段）
        if (message.toolCall != null) return true
        if (message.segments?.any { it.type == "tool" } == true) return true

        // 规则4：含代码块
        if (message.text.contains(CODE_FENCE)) return true

        // 规则3：用户决策消息（仅对 USER 角色检查关键词，避免误判模型输出）
        if (message.participant == Participant.USER && containsDecisionKeyword(message.text)) {
            return true
        }

        return false
    }

    /**
     * 将消息列表分离为（重要消息, 可摘要消息）。
     *
     * 两个子列表的相对顺序均与原列表一致，且并集等于原列表（按 id 去重后）。
     *
     * @param messages 原始消息列表。
     * @return Pair(重要消息列表, 可摘要消息列表)。
     */
    fun partition(messages: List<ChatMessage>): Pair<List<ChatMessage>, List<ChatMessage>> {
        if (messages.isEmpty()) return emptyList<ChatMessage>() to emptyList()
        val important = mutableListOf<ChatMessage>()
        val summarizable = mutableListOf<ChatMessage>()
        for (message in messages) {
            if (isImportant(message)) important.add(message) else summarizable.add(message)
        }
        return important to summarizable
    }

    /**
     * 按原始顺序重组：重要消息原样保留，可摘要消息区域用摘要结果替换。
     *
     * 重组规则：
     * - 遍历原始消息列表，重要消息按原位输出；
     * - 可摘要消息中"被摘要掉"的部分（即不在 [summarizedResult] 中的）跳过；
     * - [summarizedResult] 中的摘要消息（compact_ 前缀）放在第一个被跳过的可摘要消息位置；
     * - [summarizedResult] 中保留的后缀消息（与原可摘要消息 id 匹配）按原位输出。
     *
     * @param original 原始消息列表（partition 的输入）。
     * @param important 重要消息列表（partition 的第一个返回值）。
     * @param summarizedResult 可摘要消息经摘要后的结果
     *        （通常是 [摘要消息] + 保留的后缀，或仅保留的后缀）。
     * @return 重组后的消息列表，顺序与 [original] 一致（重要消息原位，可摘要区域被替换）。
     */
    fun reassemble(
        original: List<ChatMessage>,
        important: List<ChatMessage>,
        summarizedResult: List<ChatMessage>,
    ): List<ChatMessage> {
        if (original.isEmpty()) return summarizedResult
        if (important.isEmpty()) return summarizedResult

        val importantIds = important.mapTo(mutableSetOf()) { it.id }
        // summarizedResult 中与原可摘要消息 id 匹配的是保留的后缀；其余是新生成的摘要消息
        val originalIds = original.mapTo(mutableSetOf()) { it.id }
        val retainedSuffixIds = summarizedResult
            .map { it.id }
            .filter { it in originalIds }
            .toMutableSet()
        // 新生成的摘要消息（不在 original 中的，通常是 compact_ 前缀）
        val newSummaryMessages = summarizedResult.filter { it.id !in retainedSuffixIds }

        val result = mutableListOf<ChatMessage>()
        var summaryPlaced = newSummaryMessages.isEmpty() // 无摘要消息时标记为已放置
        for (message in original) {
            when {
                // 重要消息原样保留
                message.id in importantIds -> result.add(message)
                // 保留的后缀消息按原位输出
                message.id in retainedSuffixIds -> result.add(message)
                // 被摘要掉的可摘要消息：在第一个被跳过的位置插入新生成的摘要消息
                !summaryPlaced -> {
                    result.addAll(newSummaryMessages)
                    summaryPlaced = true
                }
                // 其余被摘要掉的消息直接跳过
                else -> Unit
            }
        }
        // 如果所有可摘要消息都被摘要掉且尚未放置摘要消息（边界情况），追加到末尾
        if (!summaryPlaced) {
            result.addAll(newSummaryMessages)
        }
        return result
    }

    /** 检查文本是否包含任一决策关键词（大小写不敏感）。 */
    private fun containsDecisionKeyword(text: String): Boolean {
        if (text.isBlank()) return false
        val lower = text.lowercase()
        return DECISION_KEYWORDS.any { keyword ->
            if (keyword.any(Char::isUpperCase)) text.contains(keyword) else lower.contains(keyword)
        }
    }


    /** 便捷访问工具协议消息判定（避免跨包 internal 可见性问题）。 */
    private fun ChatMessage.isToolProtocolMessage(): Boolean =
        id.startsWith(Constants.TOOL_MSG_PREFIX) || id.startsWith(Constants.RESULT_MSG_PREFIX)

    /** 便捷访问 compact 摘要消息判定。 */
    private fun ChatMessage.isContextCompact(): Boolean =
        id.startsWith(Constants.COMPACT_MSG_PREFIX)
}