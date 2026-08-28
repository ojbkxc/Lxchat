package com.lxseek.chat.api.context

import com.lxseek.chat.api.util.ContextTokenEstimator
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
 *     │  ├─ SelectiveRetention.partition  （分离重要消息，摘要时跳过）
 *     │  ├─ 动态 retainRecent 计算        （按 token 预算 30% 估算）
 *     │  └─ 递归摘要（prefix 过长时分块摘要再合并）
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
     * 第4层增强（选择性保留 + 递归摘要 + 动态 retainRecent）：
     * 1. 选择性保留：用 [SelectiveRetention.partition] 分离重要消息（工具调用/用户决策/代码块/
     *    已有摘要），只对可摘要消息应用 [AutoSummarizer.plan]，重要消息原样保留并按原始顺序重组；
     * 2. 动态 retainRecent：根据 token 预算动态计算保留的最近消息数，保证至少 30% 预算留给最近消息；
     * 3. 递归摘要：当可摘要前缀的估算 token 超过 [ContextGuardConfig.maxSummaryTokens] 时，
     *    [AutoSummarizer.plan] 返回 chunks，通过 [SummaryGenerator.generateRecursive] 分块摘要再合并。
     *
     * 向后兼容：[ContextGuardConfig.enableSelectiveRetention] 默认 true 但行为是"分离后重组"，
     *   无重要消息时等价于不分离；[ContextGuardConfig.maxSummaryTokens] 默认 2000 足够大，
     *   短对话不会触发递归；[ContextGuardConfig.dynamicRetainRecent] 默认 true，但会与
     *   [ContextGuardConfig.retainRecentMessages] 取合理值，短对话下结果一致。
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

        // ── 选择性保留：分离重要消息和可摘要消息 ──
        val (important, summarizable) = if (config.enableSelectiveRetention) {
            SelectiveRetention.partition(syncResult.messages)
        } else {
            emptyList<ChatMessage>() to syncResult.messages
        }

        // 可摘要消息为空（全部重要）时无需摘要
        if (summarizable.isEmpty()) return syncResult

        // ── 动态 retainRecent：根据 token 预算估算保留的最近消息数 ──
        val retainRecent = if (config.dynamicRetainRecent) {
            computeDynamicRetainRecent(summarizable, config)
        } else {
            config.retainRecentMessages
        }

        // ── 规划摘要（含递归摘要的 chunk 切分） ──
        val plan = AutoSummarizer.plan(
            messages = summarizable,
            retainRecent = retainRecent,
            maxSummaryTokens = config.maxSummaryTokens,
        )
        if (plan !is SummaryPlan.Requested) return syncResult

        val summaryPrompt = config.summaryPrompt.ifBlank { AutoSummarizer.DEFAULT_SUMMARY_PROMPT }

        // ── 生成摘要：递归摘要（chunks 非空）或单次摘要 ──
        val summaryText = if (plan.chunks != null) {
            try {
                summaryGenerator.generateRecursive(plan.chunks, summaryPrompt)
            } catch (_: Exception) {
                null
            }
        } else {
            try {
                summaryGenerator.generate(plan.prefixToSummarize, summaryPrompt)
            } catch (_: Exception) {
                null
            }
        }

        if (summaryText.isNullOrBlank()) {
            return syncResult.copy(
                events = syncResult.events + GuardEvent.AutoSummaryFailed("摘要生成返回空"),
            )
        }

        val parentId = plan.prefixToSummarize.lastOrNull()?.id
        val summarizedResult = AutoSummarizer.assembleSummaryMessage(
            summaryText = summaryText,
            parentId = parentId,
            suffixToRetain = plan.suffixToRetain,
        )

        // ── 重组：重要消息原样保留，可摘要区域用摘要结果替换 ──
        val finalMessages = if (config.enableSelectiveRetention && important.isNotEmpty()) {
            SelectiveRetention.reassemble(
                original = syncResult.messages,
                important = important,
                summarizedResult = summarizedResult,
            )
        } else {
            summarizedResult
        }

        val summarizedCount = plan.prefixToSummarize.size
        return syncResult.copy(
            messages = finalMessages,
            events = syncResult.events + GuardEvent.AutoSummaryApplied(
                summarizedCount = summarizedCount,
                retainedCount = plan.suffixToRetain.size + important.size,
                summaryLength = summaryText.length,
            ),
        )
    }

    /**
     * 动态计算 retainRecent：根据 token 预算估算保留的最近消息数。
     *
     * 规则：
     * - 估算每条消息的平均 token 数；
     * - retainRecent = max(4, (tokenBudget * 0.3) / avgTokensPerMessage)；
     * - 保留至少 30% 的 token 预算给最近消息，且至少保留 4 条消息。
     *
     * @param messages 可摘要消息列表。
     * @param config 防护配置。
     * @return 动态计算的 retainRecent 值。
     */
    private fun computeDynamicRetainRecent(
        messages: List<ChatMessage>,
        config: ContextGuardConfig,
    ): Int {
        if (messages.isEmpty()) return config.retainRecentMessages
        val tokenBudget = ContextBudget.normalize(config.tokenBudget)
        val totalTokens = ContextTokenEstimator.estimate(messages)
        if (totalTokens <= 0) return config.retainRecentMessages
        val avgTokensPerMessage = totalTokens.toDouble() / messages.size
        if (avgTokensPerMessage <= 0.0) return config.retainRecentMessages
        // 保留 30% 的 token 预算给最近消息
        val budgetForRecent = tokenBudget * 0.3
        val dynamic = (budgetForRecent / avgTokensPerMessage).toInt()
        // 至少保留 4 条，且不超过消息总数
        return maxOf(4, dynamic).coerceAtMost(messages.size)
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
    /**
     * 第4层：是否启用选择性保留（重要消息不摘要）。
     *
     * 默认 true。启用后用 [SelectiveRetention] 分离重要消息（工具调用/用户决策/代码块/已有摘要），
     * 只对可摘要消息应用摘要，重要消息原样保留并按原始顺序重组。
     * 无重要消息时行为与不启用一致（向后兼容）。
     */
    val enableSelectiveRetention: Boolean = true,
    /**
     * 第4层：递归摘要阈值（token 数）。
     *
     * 可摘要前缀的估算 token 超过此值时，切分为多个 chunk 分别摘要再合并。
     * 默认 2000，短对话不会触发递归（向后兼容）。<= 0 关闭递归摘要。
     */
    val maxSummaryTokens: Int = AutoSummarizer.DEFAULT_MAX_SUMMARY_TOKENS,
    /**
     * 第4层：是否启用动态 retainRecent。
     *
     * 默认 true。启用后根据 token 预算动态计算保留的最近消息数
     * （max(4, tokenBudget * 0.3 / avgTokensPerMessage)），保证至少 30% 预算留给最近消息。
     * 关闭时使用固定的 [retainRecentMessages]。
     */
    val dynamicRetainRecent: Boolean = true,
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
 * 选择性保留配置（语义分组，便于调用方按需构造）。
 *
 * 可通过 [ContextGuardConfig.copy] 配合此对象设置第4层的选择性保留参数，
 * 也可直接在 [ContextGuardConfig] 构造时传入对应字段。
 */
data class SelectiveRetentionConfig(
    /** 是否启用选择性保留（重要消息不摘要）。 */
    val enableSelectiveRetention: Boolean = true,
    /** 递归摘要阈值（token 数）。 */
    val maxSummaryTokens: Int = AutoSummarizer.DEFAULT_MAX_SUMMARY_TOKENS,
    /** 是否启用动态 retainRecent。 */
    val dynamicRetainRecent: Boolean = true,
) {
    companion object {
        /** 默认配置：全部启用。 */
        val Default = SelectiveRetentionConfig()

        /** 应用到 [ContextGuardConfig] 的便捷方法。 */
        fun applyTo(
            config: ContextGuardConfig,
            selective: SelectiveRetentionConfig = Default,
        ): ContextGuardConfig = config.copy(
            enableSelectiveRetention = selective.enableSelectiveRetention,
            maxSummaryTokens = selective.maxSummaryTokens,
            dynamicRetainRecent = selective.dynamicRetainRecent,
        )
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
