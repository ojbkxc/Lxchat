package com.lxseek.chat.api.context

import com.lxseek.chat.api.util.ContextTokenEstimator
import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.ContextBudget

/**
 * 第3层防护：Token 预算防护器。
 *
 * 设计意图：
 * - 灵感来自 HermesApp `EnhancedAIService` 的 `beforeNextTurnLambda`：
 *   ```kotlin
 *   val usageRatio = currentTokens.toDouble() / maxTokens.toDouble()
 *   if (usageRatio >= tokenUsageThreshold) {  // 默认 0.85
 *       onTokenLimitExceeded?.invoke()
 *       ...
 *   }
 *   ```
 * - 在 Lxchat 中将其前置为"发送前检查"：在调用 Provider 之前估算当前上下文的 token 占用，
 *   返回 [GuardDecision] 告知调用方是否需要触发压缩或中止。
 * - 复用 Lxchat 已有的 [ContextTokenEstimator] 和 [ContextBudget]，不引入新依赖。
 * - 纯函数实现，决策逻辑可测试。
 *
 * 决策规则：
 * 1. 估算当前消息列表的 token 数；
 * 2. 计算 usageRatio = estimatedTokens / tokenBudget；
 * 3. ratio < [warnThreshold]  → [GuardDecision.Allow]（正常发送）；
 * 4. [warnThreshold] <= ratio < [compactThreshold]  → [GuardDecision.Warn]（告警但仍发送）；
 * 5. ratio >= [compactThreshold]  → [GuardDecision.TriggerCompact]（需触发自动摘要）。
 */
object TokenBudgetGuard {

    /** 默认告警阈值（70%）。低于此值视为上下文宽裕。 */
    const val DEFAULT_WARN_THRESHOLD = 0.70f

    /** 默认压缩阈值（85%）。灵感来自 HermesApp 的 DEFAULT_SUMMARY_TOKEN_THRESHOLD = 0.85f。 */
    const val DEFAULT_COMPACT_THRESHOLD = 0.85f

    /**
     * 评估当前上下文的 token 预算状态。
     *
     * @param messages 待发送的消息列表。
     * @param tokenBudget 上下文 token 预算（应已通过 [ContextBudget.normalize] 处理）。
     * @param warnThreshold 告警阈值，[0, 1]。
     * @param compactThreshold 压缩阈值，[0, 1]。
     * @return 防护决策。
     */
    fun evaluate(
        messages: List<ChatMessage>,
        tokenBudget: Int,
        warnThreshold: Float = DEFAULT_WARN_THRESHOLD,
        compactThreshold: Float = DEFAULT_COMPACT_THRESHOLD,
    ): GuardDecision {
        if (messages.isEmpty() || tokenBudget <= 0) return GuardDecision.Allow(0, 0f)

        val estimatedTokens = ContextTokenEstimator.estimate(messages)
        val ratio = (estimatedTokens.toFloat() / tokenBudget).coerceIn(0f, Float.MAX_VALUE)

        return when {
            ratio >= compactThreshold -> GuardDecision.TriggerCompact(
                estimatedTokens = estimatedTokens,
                tokenBudget = tokenBudget,
                usageRatio = ratio,
            )
            ratio >= warnThreshold -> GuardDecision.Warn(
                estimatedTokens = estimatedTokens,
                usageRatio = ratio,
            )
            else -> GuardDecision.Allow(
                estimatedTokens = estimatedTokens,
                usageRatio = ratio,
            )
        }
    }

    /** 是否需要触发压缩。便捷方法。 */
    fun needsCompact(
        messages: List<ChatMessage>,
        tokenBudget: Int,
        compactThreshold: Float = DEFAULT_COMPACT_THRESHOLD,
    ): Boolean = evaluate(messages, tokenBudget, compactThreshold = compactThreshold) is GuardDecision.TriggerCompact
}

/**
 * Token 预算防护决策。
 *
 * 使用 sealed interface 保持 Lxchat 的代码风格，调用方可用 when 穷尽匹配。
 */
sealed interface GuardDecision {
    /** 估算的 token 数。 */
    val estimatedTokens: Int

    /** 占用比例（estimatedTokens / tokenBudget）。 */
    val usageRatio: Float

    /** 正常发送：上下文宽裕。 */
    data class Allow(
        override val estimatedTokens: Int,
        override val usageRatio: Float,
    ) : GuardDecision

    /** 告警：上下文偏紧，但仍可发送。UI 可展示提示。 */
    data class Warn(
        override val estimatedTokens: Int,
        override val usageRatio: Float,
    ) : GuardDecision

    /** 触发压缩：上下文已超阈值，建议先执行自动摘要再发送。 */
    data class TriggerCompact(
        override val estimatedTokens: Int,
        val tokenBudget: Int,
        override val usageRatio: Float,
    ) : GuardDecision
}