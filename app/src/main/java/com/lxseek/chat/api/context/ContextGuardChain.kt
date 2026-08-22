package com.lxseek.chat.api.context

import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.ContextBudget

/**
 * Context 4层防护链：整合入口。
 *
 * 设计意图：
 * - 将 HermesApp 的 4 层 Context 防护机制深度融合到 Lxchat 的消息处理流程：
 *   1. [HistoryTurnLimiter]  — 限制历史轮数（粗筛）
 *   2. [ToolResultTrimmer]   — 裁剪超长工具结果（减体积）
 *   3. [TokenBudgetGuard]    — 检查 token 预算（决策）
 *   4. [AutoSummarizer]      — 自动摘要压缩（兜底）
 * - 防护链在 `prepareMessages` 之前应用，所有 Provider 自动受益；
 * - 默认配置下防护链"透明"：[ContextGuardConfig.disabled] 返回原消息，保持向后兼容。
 *
 * 执行顺序：
 * ```
 * 原始消息
 *   → 第1层：HistoryTurnLimiter.limit     （按轮数粗筛）
 *   → 第2层：ToolResultTrimmer.trim       （裁剪工具结果）
 *   → 第3层：TokenBudgetGuard.evaluate    （检查 token 预算）
 *   → 第4层：AutoSummarizer.plan + generate （如需，生成摘要）
 *   → 输出：GuardResult（处理后的消息 + 事件列表）
 * ```
 */
object ContextGuardChain {

    /**
     * 同步应用前 3 层防护（轮数限制 + 工具结果裁剪 + token 预算检查）。
     *
     * 第4层（自动摘要）需要调用 LLM，是异步操作，通过 [applyAsync] 使用。
     * 本方法适用于不需要自动摘要的场景，或在异步摘要之前先做同步预处理。
     *
     * @param messages 原始消息列表。
     * @param config 防护配置。
     * @return 防护结果。
     */
    fun apply(
        messages: List<ChatMessage>,
        config: ContextGuardConfig,
    ): GuardResult {
        if (config.disabled || messages.isEmpty()) {
            return GuardResult(
                messages = messages,
                events = emptyList(),
                decision = if (messages.isEmpty()) GuardDecision.Allow(0, 0f) else null,
            )
        }

        val events = mutableListOf<GuardEvent>()

        // 第1层：历史轮数限制
        val afterLayer1 = if (config.maxHistoryTurns > 0) {
            val before = messages.size
            val limited = HistoryTurnLimiter.limit(messages, config.maxHistoryTurns)
            if (limited.size < before) {
                events.add(GuardEvent.HistoryTurnsLimited(
                    beforeCount = before,
                    afterCount = limited.size,
                    maxTurns = config.maxHistoryTurns,
                ))
            }
            limited
        } else {
            messages
        }

        // 第2层：工具结果裁剪
        val afterLayer2 = if (config.maxToolResultChars > 0) {

            val trimmed = ToolResultTrimmer.trim(
                afterLayer1,
                maxResultChars = config.maxToolResultChars,
                headChars = config.toolResultHeadChars,
                tailChars = config.toolResultTailChars,
            )
            if (trimmed != afterLayer1) {
                events.add(GuardEvent.ToolResultsTrimmed(
                    maxResultChars = config.maxToolResultChars,
                    headChars = config.toolResultHeadChars,
                    tailChars = config.toolResultTailChars,
                ))
            }
            trimmed
        } else {
            afterLayer1
        }

        // 第3层：Token 预算检查
        val tokenBudget = ContextBudget.normalize(config.tokenBudget)
        val decision = TokenBudgetGuard.evaluate(
            messages = afterLayer2,
            tokenBudget = tokenBudget,
            warnThreshold = config.warnThreshold,
            compactThreshold = config.compactThreshold,
        )
        when (decision) {
            is GuardDecision.Warn -> events.add(GuardEvent.TokenBudgetWarn(
                estimatedTokens = decision.estimatedTokens,
                usageRatio = decision.usageRatio,
            ))
            is GuardDecision.TriggerCompact -> events.add(GuardEvent.TokenBudgetExceeded(
                estimatedTokens = decision.estimatedTokens,
                tokenBudget = decision.tokenBudget,
                usageRatio = decision.usageRatio,
            ))
            else -> Unit
        }

        return GuardResult(
            messages = afterLayer2,
            events = events,
            decision = decision,
        )
    }

    /**
     * 异步应用全部 4 层防护（含自动摘要）。
     *
     * @param messages 原始消息列表。
     * @param config 防护配置。
     * @param summaryGenerator 摘要生成器（第4层使用）；为 null 时跳过第4层。
     * @return 防护结果。
     */
    suspend fun applyAsync(
        messages: List<ChatMessage>,
        config: ContextGuardConfig,
        summaryGenerator: SummaryGenerator? = null,
    ): GuardResult {
        // 先执行前 3 层
        val syncResult = apply(messages, config)

        // 第4层：自动摘要（仅当 token 超阈值且提供了生成器时触发）
        if (summaryGenerator == null || config.disabled) return syncResult

        val triggerCompact = syncResult.decision as? GuardDecision.TriggerCompact
            ?: return syncResult

        if (!config.autoSummarizeEnabled) return syncResult

        val plan = AutoSummarizer.plan(syncResult.messages, config.retainRecentMessages)
        if (plan !is SummaryPlan.Requested) return syncResult

        val summaryPrompt = config.summaryPrompt.ifBlank { AutoSummarizer.DEFAULT_SUMMARY_PROMPT }
        val summaryText = try {
            summaryGenerator.generate(plan.prefixToSummarize, summaryPrompt)
        } catch (_: Exception) {
            null
        }

        if (summaryText.isNullOrBlank()) {
            return syncResult.copy(
                events = syncResult.events + GuardEvent.AutoSummaryFailed("摘要生成返回空"),
            )
        }

        val parentId = plan.prefixToSummarize.lastOrNull()?.id
        val summarized = AutoSummarizer.assembleSummaryMessage(
            summaryText = summaryText,
            parentId = parentId,
            suffixToRetain = plan.suffixToRetain,
        )

        return syncResult.copy(
            messages = summarized,
            events = syncResult.events + GuardEvent.AutoSummaryApplied(
                summarizedCount = plan.prefixToSummarize.size,
                retainedCount = plan.suffixToRetain.size,
                summaryLength = summaryText.length,
            ),
        )
    }
}

/**
 * 防护链配置。
 *
 * 所有阈值都有合理默认值，[disabled] = true 时完全关闭防护链。
 */
data class ContextGuardConfig(
    /** 完全关闭防护链。 */
    val disabled: Boolean = false,
    /** 第1层：保留的最近用户轮数；<= 0 关闭本层。 */
    val maxHistoryTurns: Int = HistoryTurnLimiter.DEFAULT_MAX_TURNS,
    /** 第2层：工具结果裁剪阈值（字符数）；<= 0 关闭本层。 */
    val maxToolResultChars: Int = ToolResultTrimmer.DEFAULT_MAX_RESULT_CHARS,
    /** 第2层：保留的头部字符数。 */
    val toolResultHeadChars: Int = ToolResultTrimmer.DEFAULT_HEAD_CHARS,
    /** 第2层：保留的尾部字符数。 */
    val toolResultTailChars: Int = ToolResultTrimmer.DEFAULT_TAIL_CHARS,
    /** 第3层：token 预算（应来自 ProviderConfig.maxContextWindow）。 */
    val tokenBudget: Int = ContextBudget.DEFAULT_TOKENS,
    /** 第3层：告警阈值。 */
    val warnThreshold: Float = TokenBudgetGuard.DEFAULT_WARN_THRESHOLD,
    /** 第3层：压缩阈值。 */
    val compactThreshold: Float = TokenBudgetGuard.DEFAULT_COMPACT_THRESHOLD,
    /** 第4层：是否启用自动摘要。 */
    val autoSummarizeEnabled: Boolean = true,
    /** 第4层：保留的最近逻辑消息数。 */
    val retainRecentMessages: Int = AutoSummarizer.DEFAULT_RETAIN_RECENT,
    /** 第4层：摘要系统提示词。 */
    val summaryPrompt: String = AutoSummarizer.DEFAULT_SUMMARY_PROMPT,
) {
    companion object {
        /**
         * 默认配置：启用第2、3、4层，关闭第1层（轮数限制）。
         *
         * 第1层默认关闭是因为 Lxchat 已有基于 token 的 `limitContext` 滑动窗口，
         * 轮数限制作为可选的粗筛层，由调用方按需开启。
         */
        val Default = ContextGuardConfig()

        /** 完全关闭防护链的配置。 */
        val Off = ContextGuardConfig(disabled = true)
    }
}

/**
 * 防护事件：记录防护链中各层的触发情况。
 *
 * 使用 sealed interface 保持 Lxchat 的代码风格，调用方可用于日志/UI 展示。
 */
sealed interface GuardEvent {
    /** 第1层触发：历史轮数被限制。 */
    data class HistoryTurnsLimited(
        val beforeCount: Int,
        val afterCount: Int,
        val maxTurns: Int,
    ) : GuardEvent

    /** 第2层触发：工具结果被裁剪。 */
    data class ToolResultsTrimmed(
        val maxResultChars: Int,
        val headChars: Int,
        val tailChars: Int,
    ) : GuardEvent

    /** 第3层触发：token 预算告警。 */
    data class TokenBudgetWarn(
        val estimatedTokens: Int,
        val usageRatio: Float,
    ) : GuardEvent

    /** 第3层触发：token 预算超阈值。 */
    data class TokenBudgetExceeded(
        val estimatedTokens: Int,
        val tokenBudget: Int,
        val usageRatio: Float,
    ) : GuardEvent

    /** 第4层触发：自动摘要已应用。 */
    data class AutoSummaryApplied(
        val summarizedCount: Int,
        val retainedCount: Int,
        val summaryLength: Int,
    ) : GuardEvent

    /** 第4层触发：自动摘要失败。 */
    data class AutoSummaryFailed(val reason: String) : GuardEvent
}

/**
 * 防护链结果。
 *
 * @param messages 处理后的消息列表（供后续 `prepareMessages` / Provider 使用）。
 * @param events 防护事件列表（按执行顺序记录）。
 * @param decision 第3层的 token 预算决策；可为 null（输入为空时）。
 */
data class GuardResult(
    val messages: List<ChatMessage>,
    val events: List<GuardEvent>,
    val decision: GuardDecision?,
)