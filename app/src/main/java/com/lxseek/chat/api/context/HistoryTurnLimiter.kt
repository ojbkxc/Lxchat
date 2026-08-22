package com.lxseek.chat.api.context

import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.Participant
import com.lxseek.chat.util.Constants

/**
 * 第1层防护：历史轮数限制器。
 *
 * 设计意图：
 * - 灵感来自 HermesApp 的 `limitImageLinksInChatHistory` / `limitMediaLinksInChatHistory`，
 *   那里通过 `maxImageHistoryUserTurns` / `maxMediaHistoryUserTurns` 限制带媒体的历史轮数。
 * - 在 Lxchat 中将其泛化为"限制发送给 LLM 的历史对话轮数"，作为 token 滑动窗口
 *   （`limitContext`）之前的粗筛层：先按轮数砍掉过老的消息，再由 token 预算精筛。
 * - 纯函数实现，无副作用，不引入新依赖，保持 Lxchat 的 MVVM + Coroutines 风格。
 *
 * 规则：
 * 1. 工具协议行（tool_/result_）和 compact 摘要行始终保留，不参与轮数计数；
 * 2. 仅对普通 user/model 消息计数"用户轮次"（一个用户轮次 = 一条 user 消息及其后续 assistant 回复）；
 * 3. 保留最后 [maxTurns] 个用户轮次；更早的轮次被丢弃；
 * 4. 始终保留第一条普通 user 消息（多数 provider 要求 user 开头）；
 * 5. [maxTurns] <= 0 时视为关闭本层防护，原样返回。
 */
object HistoryTurnLimiter {

    /** 默认保留的最近用户轮数。0 表示关闭本层。 */
    const val DEFAULT_MAX_TURNS = 0

    /**
     * 按轮数限制历史。
     *
     * @param messages 已规范化的消息列表（调用方应先去重、投影状态）。
     * @param maxTurns 保留的最近用户轮数；<= 0 时直接返回原列表。
     * @return 裁剪后的消息列表，保持原始顺序。
     */
    fun limit(messages: List<ChatMessage>, maxTurns: Int): List<ChatMessage> {
        if (maxTurns <= 0 || messages.isEmpty()) return messages

        // 1. 找到所有"普通用户消息"的索引（排除工具协议行和 compact 行）
        val normalUserIndices = messages.indices.filter { idx ->
            messages[idx].isNormalLogicalMessage() && messages[idx].participant == Participant.USER
        }
        if (normalUserIndices.size <= maxTurns) return messages

        // 2. 计算保留起点：第 (size - maxTurns) 个用户消息的索引
        val keepFromUserIndex = normalUserIndices[normalUserIndices.size - maxTurns]

        // 3. 找到第一条普通 user 消息（provider 要求 user 开头的锚点）
        val firstUserIndex = normalUserIndices.firstOrNull() ?: return messages

        // 4. 构建保留集合：第一条 user + [keepFromUserIndex, end)
        val retainedIndices = linkedSetOf<Int>()
        retainedIndices.add(firstUserIndex)
        for (i in keepFromUserIndex until messages.size) {
            retainedIndices.add(i)
        }

        // 5. 同时保留所有在保留区间内的工具协议行和 compact 行；
        //    区间外的 compact 行也保留（它是上下文边界，丢弃会丢失摘要）。
        val result = mutableListOf<ChatMessage>()
        for (i in messages.indices) {
            val msg = messages[i]
            when {
                msg.id.startsWith(Constants.COMPACT_MSG_PREFIX) -> result.add(msg)
                msg.isToolProtocolMessage() && i >= keepFromUserIndex -> result.add(msg)
                i in retainedIndices -> result.add(msg)
            }
        }
        return result
    }

    /** 是否为工具协议行（tool_ / result_）。 */
    private fun ChatMessage.isToolProtocolMessage(): Boolean =
        id.startsWith(Constants.TOOL_MSG_PREFIX) || id.startsWith(Constants.RESULT_MSG_PREFIX)

    /** 是否为参与轮数计数的普通逻辑消息（非工具协议、非 compact）。 */
    private fun ChatMessage.isNormalLogicalMessage(): Boolean =
        !isToolProtocolMessage() && !id.startsWith(Constants.COMPACT_MSG_PREFIX)
}