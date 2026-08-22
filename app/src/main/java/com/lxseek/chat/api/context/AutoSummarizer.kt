package com.lxseek.chat.api.context

import com.lxseek.chat.api.util.splitLogicalContext
import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.MessageStatus
import com.lxseek.chat.model.Participant
import com.lxseek.chat.util.Constants
import java.util.UUID

/**
 * 第4层防护：自动摘要器。
 *
 * 设计意图：
 * - 灵感来自 HermesApp `AIMessageManager.summarizeMemory`：
 *   1. 找到上一次的 summary 消息（sender == "summary"）；
 *   2. 提取需要总结的 user + ai 消息；
 *   3. 调用 `enhancedAiService.generateSummary` 生成摘要；
 *   4. 摘要消息以 sender="summary" 插入历史。
 * - 在 Lxchat 中复用已有的 [splitLogicalContext] 进行前缀/后缀分割，
 *   并通过 [SummaryGenerator] 接口委托实际 LLM 调用给调用方
 *   （通常复用 Lxchat 的 `ContextCompactor` / `LlmProvider`）。
 * - 本对象只负责"决定哪些消息需要被摘要"和"组装摘要消息"，不直接调用 LLM，
 *   保持纯函数特性，便于测试和复用。
 *
 * 规则：
 * 1. 用 [splitLogicalContext] 将消息分为 prefix（待摘要）和 suffix（保留）；
 * 2. prefix 为空时返回 [SummaryPlan.NotNeeded]；
 * 3. 否则返回 [SummaryPlan.Requested]，调用方据此调用 [SummaryGenerator.generate]；
 * 4. 生成的摘要消息使用 `compact_` 前缀（与 Lxchat 的 [Constants.COMPACT_MSG_PREFIX] 一致），
 *    以复用现有的 `applyNearestContextCompact` 投影逻辑。
 */
object AutoSummarizer {

    /** 默认保留的最近逻辑消息数（后缀长度）。 */
    const val DEFAULT_RETAIN_RECENT = 6

    /** 默认的摘要系统提示词。 */
    val DEFAULT_SUMMARY_PROMPT = """
        你是负责生成对话摘要的AI助手。请根据以下对话内容，生成一份精简、抓重点的摘要。
        摘要要求：
        1. 保留核心任务状态和关键决策点；
        2. 保留重要的硬约束和用户偏好；
        3. 不复述原文，只提炼关键信息；
        4. 摘要应自包含，仅凭摘要即可理解"在做什么 + 还要做什么"。
    """.trimIndent()

    /**
     * 规划摘要：决定哪些消息需要被摘要，哪些保留。
     *
     * @param messages 已规范化的消息列表。
     * @param retainRecent 保留的最近逻辑消息数。
     * @return 摘要计划。
     */
    fun plan(
        messages: List<ChatMessage>,
        retainRecent: Int = DEFAULT_RETAIN_RECENT,
    ): SummaryPlan {
        if (messages.isEmpty() || retainRecent < 0) return SummaryPlan.NotNeeded

        val split = splitLogicalContext(messages, retainRecent)
        if (split.prefix.isEmpty()) return SummaryPlan.NotNeeded

        return SummaryPlan.Requested(
            prefixToSummarize = split.prefix,
            suffixToRetain = split.suffix,
        )
    }

    /**
     * 将生成的摘要文本组装为 compact 消息。
     *
     * 使用 `compact_` 前缀，与 Lxchat 现有的上下文压缩边界一致，
     * 这样 `applyNearestContextCompact` 会自动将其作为上下文起点。
     *
     * @param summaryText LLM 生成的摘要文本。
     * @param parentId 父消息 ID（通常是 prefix 的最后一条消息）。
     * @return compact 消息 + 保留的后缀。
     */
    fun assembleSummaryMessage(
        summaryText: String,
        parentId: String?,
        suffixToRetain: List<ChatMessage>,
    ): List<ChatMessage> {
        if (summaryText.isBlank()) return suffixToRetain
        val compactId = Constants.COMPACT_MSG_PREFIX + UUID.randomUUID()
        val now = System.currentTimeMillis()
        val compactMessage = ChatMessage(
            id = compactId,
            parentId = parentId,
            text = summaryText.trim(),
            participant = Participant.MODEL,
            status = MessageStatus.SUCCESS,
            timestamp = now,
            modelName = "auto-summary",
        )
        return listOf(compactMessage) + suffixToRetain
    }
}

/**
 * 摘要计划。
 */
sealed interface SummaryPlan {
    /** 无需摘要：消息量不足以触发压缩。 */
    data object NotNeeded : SummaryPlan

    /** 需要摘要：prefix 需被摘要，suffix 保留。 */
    data class Requested(
        val prefixToSummarize: List<ChatMessage>,
        val suffixToRetain: List<ChatMessage>,
    ) : SummaryPlan
}

/**
 * 摘要生成器接口。
 *
 * 由调用方实现，委托实际 LLM 调用。典型实现复用 Lxchat 的 `ContextCompactor` 或直接调用 `LlmProvider`。
 * 这样 AutoSummarizer 本身保持纯函数，不耦合具体的 Provider 实现。
 */
fun interface SummaryGenerator {
    /**
     * 生成摘要。
     *
     * @param prefixToSummarize 待摘要的消息前缀。
     * @param summaryPrompt 摘要系统提示词。
     * @return 生成的摘要文本；失败时返回 null。
     */
    suspend fun generate(
        prefixToSummarize: List<ChatMessage>,
        summaryPrompt: String,
    ): String?
}