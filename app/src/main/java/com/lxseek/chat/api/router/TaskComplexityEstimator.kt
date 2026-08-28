package com.lxseek.chat.api.router

import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.util.DebugLog

/**
 * 任务复杂度等级
 *
 * 用于智能路由按复杂度选择模型：简单任务用小模型省 token，复杂任务用大模型。
 * [weight] 表示相对开销/能力权重，越大越复杂。
 */
sealed interface ComplexityLevel {
    /** 权重：相对开销/能力指标，越大越复杂。 */
    val weight: Double

    /** 简单任务：翻译、总结、格式化等，用小模型即可。 */
    data object Simple : ComplexityLevel {
        override val weight: Double = 0.3
    }

    /** 中等任务：常规对话、少量工具调用，用主模型。 */
    data object Medium : ComplexityLevel {
        override val weight: Double = 0.6
    }

    /** 复杂任务：分析、设计、架构、重构等，用大模型。 */
    data object Complex : ComplexityLevel {
        override val weight: Double = 1.0
    }
}

/**
 * 任务复杂度评估器
 *
 * 基于消息内容用纯规则评估任务复杂度（不调用 LLM），返回 [ComplexityLevel]。
 * 评估维度：消息长度、工具调用数、代码量、多轮对话、关键词。
 *
 * 评估策略：
 * 1. 先按结构性指标（长度/工具/代码/多轮）各自判定，取各维度最大值作为结构复杂度；
 * 2. 包含复杂关键词（分析/设计/架构/重构/优化）→ 强制 [ComplexityLevel.Complex]；
 * 3. 否则包含简单关键词（翻译/总结/格式化）→ 强制 [ComplexityLevel.Simple]；
 * 4. 否则使用结构复杂度。
 *
 * 关键词优先于结构指标：例如"请翻译这段话"即使很长也视为 Simple。
 * 但复杂关键词优先级最高，会覆盖简单关键词。
 */
object TaskComplexityEstimator {
    private const val TAG = "ComplexityEstimator"

    /** 消息长度阈值：总字符数 < [LENGTH_SIMPLE] = Simple。 */
    private const val LENGTH_SIMPLE = 500
    /** 消息长度阈值：总字符数 < [LENGTH_MEDIUM] = Medium，>= 即 Complex。 */
    private const val LENGTH_MEDIUM = 3000
    /** 工具调用数阈值：> [TOOL_COMPLEX] 个 = Complex。 */
    private const val TOOL_COMPLEX = 3
    /** 代码行数阈值：> [CODE_LINES_COMPLEX] 行 = Complex。 */
    private const val CODE_LINES_COMPLEX = 100
    /** 多轮对话阈值：消息数 > [MULTI_TURN_MEDIUM] = 至少 Medium。 */
    private const val MULTI_TURN_MEDIUM = 10

    /** 复杂任务关键词（命中 → Complex）。 */
    private val COMPLEX_KEYWORDS = listOf(
        "分析", "设计", "架构", "重构", "优化",
        "analyze", "design", "architecture", "refactor", "optimize",
    )

    /** 简单任务关键词（命中 → Simple，除非命中复杂关键词）。 */
    private val SIMPLE_KEYWORDS = listOf(
        "翻译", "总结", "格式化",
        "translate", "summarize", "format",
    )

    /**
     * 评估消息列表的任务复杂度。
     *
     * @param messages 聊天消息列表
     * @return 复杂度等级；空列表视为 [ComplexityLevel.Simple]
     */
    fun estimate(messages: List<ChatMessage>): ComplexityLevel {
        if (messages.isEmpty()) return ComplexityLevel.Simple

        // ── 结构性指标 ──
        val totalChars = totalCharCount(messages)
        val toolCalls = toolCallCount(messages)
        val codeLines = codeLineCount(messages)
        val messageCount = messages.size

        // 按长度判定：< 500 = Simple, < 3000 = Medium, >= 3000 = Complex
        val byLength: ComplexityLevel = when {
            totalChars < LENGTH_SIMPLE -> ComplexityLevel.Simple
            totalChars < LENGTH_MEDIUM -> ComplexityLevel.Medium
            else -> ComplexityLevel.Complex
        }

        // 按工具调用数判定：有工具调用 = 至少 Medium，> 3 = Complex
        val byTools: ComplexityLevel = when {
            toolCalls == 0 -> ComplexityLevel.Simple
            toolCalls > TOOL_COMPLEX -> ComplexityLevel.Complex
            else -> ComplexityLevel.Medium
        }

        // 按代码量判定：有代码块 = 至少 Medium，> 100 行 = Complex
        val byCode: ComplexityLevel = when {
            codeLines == 0 -> ComplexityLevel.Simple
            codeLines > CODE_LINES_COMPLEX -> ComplexityLevel.Complex
            else -> ComplexityLevel.Medium
        }

        // 按多轮对话判定：消息数 > 10 = 至少 Medium
        val byMultiTurn: ComplexityLevel =
            if (messageCount > MULTI_TURN_MEDIUM) ComplexityLevel.Medium else ComplexityLevel.Simple

        // 结构复杂度 = 各维度中权重最大者
        val structural = listOf(byLength, byTools, byCode, byMultiTurn)
            .reduce { acc, level -> if (level.weight > acc.weight) level else acc }

        // ── 关键词判定（优先级高于结构指标） ──
        val combinedText = messages.joinToString(separator = "\n") { it.text }
        val hasComplexKeyword = COMPLEX_KEYWORDS.any { combinedText.contains(it, ignoreCase = true) }
        val hasSimpleKeyword = SIMPLE_KEYWORDS.any { combinedText.contains(it, ignoreCase = true) }

        val result = when {
            hasComplexKeyword -> ComplexityLevel.Complex
            hasSimpleKeyword -> ComplexityLevel.Simple
            else -> structural
        }

        DebugLog.d(
            TAG,
            "复杂度=$result, 长度=$totalChars, 工具=$toolCalls, 代码行=$codeLines, " +
                "消息数=$messageCount, 复杂词=$hasComplexKeyword, 简单词=$hasSimpleKeyword",
        )
        return result
    }

    /** 累加所有消息文本的字符数。 */
    private fun totalCharCount(messages: List<ChatMessage>): Int =
        messages.sumOf { it.text.length }

    /**
     * 统计工具调用数。
     *
     * 优先用 segments 中 type=="tool" 的分段计数；若消息无 segments 但有 toolCall，则计 1。
     * 避免同一条消息同时计入 segments 与 toolCall 导致重复。
     */
    private fun toolCallCount(messages: List<ChatMessage>): Int =
        messages.sumOf { msg ->
            val segments = msg.segments
            if (!segments.isNullOrEmpty()) {
                segments.count { it.type == "tool" }
            } else if (msg.toolCall != null) {
                1
            } else {
                0
            }
        }

    /**
     * 统计所有消息文本中代码块（``` 围栏）内的总行数。
     *
     * 仅统计围栏代码块内的行数，不包含围栏标记本身。
     */
    private fun codeLineCount(messages: List<ChatMessage>): Int {
        var total = 0
        for (msg in messages) {
            total += codeLinesIn(msg.text)
        }
        return total
    }

    /** 计算单个文本中围栏代码块内的行数。 */
    private fun codeLinesIn(text: String): Int {
        var lines = 0
        var inside = false
        for (line in text.lineSequence()) {
            val trimmed = line.trimStart()
            if (trimmed.startsWith("```")) {
                inside = !inside
                continue
            }
            if (inside) lines++
        }
        return lines
    }
}