package com.lxseek.chat.api.context

import com.lxseek.chat.api.util.ContextTokenEstimator
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
 *
 * 递归摘要（超长对话）：
 * - 当 prefix 的估算 token 数超过 [maxSummaryTokens] 时，将 prefix 切分为多个 chunk，
 *   每个 chunk 约等于 [maxSummaryTokens] 大小；
 * - [SummaryPlan.Requested.chunks] 携带这些 chunk，调用方可通过
 *   [SummaryGenerator.generateRecursive] 对每个 chunk 生成摘要，再合并生成"摘要的摘要"；
 * - chunks 为 null 表示单次摘要（prefix 较短，无需递归）。
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

    /** 默认递归摘要阈值（token 数）。prefix 超过此值时触发递归摘要。 */
    const val DEFAULT_MAX_SUMMARY_TOKENS = 2000

    /**
     * 规划摘要：决定哪些消息需要被摘要，哪些保留。
     *
     * @param messages 已规范化的消息列表。
     * @param retainRecent 保留的最近逻辑消息数。
     * @param maxSummaryTokens 单次摘要的最大 token 数；prefix 超过此值时切分为多个 chunk
     *        （递归摘要）。<= 0 时关闭递归摘要，总是单次摘要。
     * @return 摘要计划。
     */
    fun plan(
        messages: List<ChatMessage>,
        retainRecent: Int = DEFAULT_RETAIN_RECENT,
        maxSummaryTokens: Int = DEFAULT_MAX_SUMMARY_TOKENS,
    ): SummaryPlan {
        if (messages.isEmpty() || retainRecent < 0) return SummaryPlan.NotNeeded

        val split = splitLogicalContext(messages, retainRecent)
        if (split.prefix.isEmpty()) return SummaryPlan.NotNeeded

        // 递归摘要：prefix 过长时切分为多个 chunk
        val chunks = if (maxSummaryTokens > 0) chunkPrefix(split.prefix, maxSummaryTokens) else null
        // chunks 为 null 或仅 1 个 chunk 时，表示无需递归，按单次摘要处理
        val effectiveChunks = chunks?.takeIf { it.size > 1 }

        return SummaryPlan.Requested(
            prefixToSummarize = split.prefix,
            suffixToRetain = split.suffix,
            chunks = effectiveChunks,
        )
    }

    /**
     * 递归摘要规划：与 [plan] 相同，但明确返回可能含 chunks 的 [SummaryPlan.Requested]。
     *
     * 当 [messages] 的 prefix 超过 [maxSummaryTokens] 时，chunks 非空，调用方应对每个 chunk
     * 分别生成摘要，再合并生成"摘要的摘要"（见 [SummaryGenerator.generateRecursive]）。
     *
     * @param messages 已规范化的消息列表。
     * @param retainRecent 保留的最近逻辑消息数。
     * @param maxSummaryTokens 单次摘要的最大 token 数。
     * @return 摘要计划（可能含 chunks）。
     */
    fun recursivePlan(
        messages: List<ChatMessage>,
        retainRecent: Int = DEFAULT_RETAIN_RECENT,
        maxSummaryTokens: Int = DEFAULT_MAX_SUMMARY_TOKENS,
    ): SummaryPlan = plan(messages, retainRecent, maxSummaryTokens)

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

    /**
     * 将多个 chunk 摘要合并为一个 compact 消息 + 保留的后缀。
     *
     * 用于递归摘要的最终组装：每个 chunk 已生成摘要文本，这里把它们拼接为一段
     * 综合摘要，再组装为 compact 消息。
     *
     * @param chunkSummaries 各 chunk 的摘要文本列表。
     * @param parentId 父消息 ID。
     * @param suffixToRetain 保留的后缀。
     * @return compact 消息 + 保留的后缀。
     */
    fun assembleRecursiveSummaryMessage(
        chunkSummaries: List<String>,
        parentId: String?,
        suffixToRetain: List<ChatMessage>,
    ): List<ChatMessage> {
        val merged = chunkSummaries
            .mapNotNull { it.takeIf(String::isNotBlank)?.trim() }
            .joinToString("\n\n---\n\n")
        return assembleSummaryMessage(merged, parentId, suffixToRetain)
    }

    /**
     * 将 prefix 切分为多个 chunk，每个 chunk 的估算 token 数约等于 [maxSummaryTokens]。
     *
     * 切分策略：按消息逐条累加 token，达到阈值即切出一个 chunk。保证每个 chunk 非空，
     * 且至少包含一条消息（即使单条消息就超阈值）。
     */
    private fun chunkPrefix(
        prefix: List<ChatMessage>,
        maxSummaryTokens: Int,
    ): List<List<ChatMessage>> {
        if (prefix.isEmpty()) return emptyList()
        val chunks = mutableListOf<MutableList<ChatMessage>>()
        var current = mutableListOf<ChatMessage>()
        var currentTokens = 0
        for (message in prefix) {
            val msgTokens = ContextTokenEstimator.estimate(listOf(message))
            current.add(message)
            currentTokens += msgTokens
            if (currentTokens >= maxSummaryTokens) {
                chunks.add(current)
                current = mutableListOf()
                currentTokens = 0
            }
        }
        if (current.isNotEmpty()) chunks.add(current)
        return chunks
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
        /**
         * 递归摘要时的 chunk 列表；null 表示单次摘要（对 [prefixToSummarize] 整体摘要）。
         *
         * 非 null 时，调用方应对每个 chunk 分别生成摘要，再合并生成"摘要的摘要"，
         * 见 [SummaryGenerator.generateRecursive]。
         */
        val chunks: List<List<ChatMessage>>? = null,
    ) : SummaryPlan
}

/**
 * 摘要生成器接口。
 *
 * 由调用方实现，委托实际 LLM 调用。典型实现复用 Lxchat 的 `ContextCompactor` 或直接调用 `LlmProvider`。
 * 这样 AutoSummarizer 本身保持纯函数，不耦合具体的 Provider 实现。
 *
 * 使用 fun interface + 默认扩展方法，保持向后兼容：现有只实现 [generate] 的实现无需改动。
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

    /**
     * 递归摘要：对多个 chunk 分别生成摘要，再合并生成"摘要的摘要"。
     *
     * 默认实现：对每个 chunk 调用 [generate] 得到 chunk 摘要，再把所有 chunk 摘要
     * 拼接为一段文本，调用 [generate] 生成最终的综合摘要。
     *
     * 调用方可覆盖此方法以使用更高效的批量策略（如一次性多文档摘要）。
     *
     * @param chunks 待摘要的消息分块列表（每块约等于 maxSummaryTokens 大小）。
     * @param summaryPrompt 摘要系统提示词。
     * @return 最终的综合摘要文本；失败时返回 null。
     */
    suspend fun generateRecursive(
        chunks: List<List<ChatMessage>>,
        summaryPrompt: String,
    ): String? {
        if (chunks.isEmpty()) return null
        // 单 chunk 直接摘要
        if (chunks.size == 1) return generate(chunks.first(), summaryPrompt)

        // 对每个 chunk 生成摘要（用 for 循环而非 mapNotNull，以支持 suspend 调用）
        val chunkSummaries = mutableListOf<String>()
        for (chunk in chunks) {
            val summary = try {
                generate(chunk, summaryPrompt)
            } catch (_: Exception) {
                null
            }
            if (!summary.isNullOrBlank()) chunkSummaries.add(summary)
        }
        if (chunkSummaries.isEmpty()) return null

        // 单个 chunk 摘要成功时直接返回（无需再合并）
        if (chunkSummaries.size == 1) return chunkSummaries.first()

        // 把各 chunk 摘要拼接为"摘要的摘要"输入文本，再生成最终摘要
        val mergedText = chunkSummaries.joinToString("\n\n---\n\n")
        // metaPrompt 只携带指示语；mergedText 通过 syntheticInput 作为待摘要内容传入，
        // 避免同一文本在系统提示与用户消息中重复出现。
        val metaPrompt = buildString {
            append(summaryPrompt)
            append("\n\n以下是分段摘要，请合并为一份连贯的综合摘要。")
        }
        // 用合成消息承载合并输入，避免引入新的消息类型
        val syntheticInput = listOf(
            ChatMessage(
                text = mergedText,
                participant = Participant.USER,
                status = MessageStatus.SUCCESS,
            )
        )
        return try {
            generate(syntheticInput, metaPrompt)
        } catch (_: Exception) {
            // 合并失败时退化为直接拼接各 chunk 摘要
            mergedText
        }
    }
}
