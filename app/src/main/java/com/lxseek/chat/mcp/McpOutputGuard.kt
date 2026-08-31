package com.lxseek.chat.mcp

import com.lxseek.chat.api.util.ContextTokenEstimator
import java.lang.Character

/**
 * Guards MCP tool output against runaway token usage, mirroring cc-haha's
 * mcpValidation.ts. Oversized text/JSON results are truncated to the budget and
 * annotated so the model knows the data was cut instead of silently reasoning
 * over a partial payload.
 *
 * The estimate is deterministic ([ContextTokenEstimator.estimateText]) rather than
 * a remote token-count API call, keeping the guard free and offline-friendly.
 *
 * 截断策略（M8 修复）：按 token 预算逐 code point 截断 —— ASCII 字母数字按 4 字符
 * 1 token、CJK 等其余字符按 1 token/字计（与 [ContextTokenEstimator] 同一套成本模型）。
 * 旧的 `maxTokens * 4` 字符换算对纯中文输出低估约 4 倍，截断后仍远超预算、形同
 * 虚设；同时截断只保留头部，尾部信息全部丢失。现在头部保留满预算，并额外保留
 * 约 10% 预算的尾部片段（M8"截断保尾部"），中间以 notice 如实标记截断发生。
 */
object McpOutputGuard {
    /** Matches cc-haha's DEFAULT_MAX_MCP_OUTPUT_TOKENS. */
    const val DEFAULT_MAX_OUTPUT_TOKENS = 25_000

    /** 截断时尾部额外保留的 token 预算比例。 */
    private const val TAIL_FRACTION_NUMERATOR = 10
    private const val TAIL_FRACTION_DENOMINATOR = 100

    data class GuardedResult(
        val text: String,
        val estimatedTokens: Int,
        val truncated: Boolean,
    )

    /** Deterministic token estimate for a single text payload. */
    fun estimateTokens(text: String): Int = ContextTokenEstimator.estimateText(text)

    /**
     * Truncate [text] when its estimated token count exceeds [maxTokens]. Returns
     * the original string unchanged when it fits within budget.
     *
     * [GuardedResult.estimatedTokens] 的口径：未截断时为全文真实估算；截断时为
     * 截断预算上限（与既有测试约定一致），是否截断由 [GuardedResult.truncated]
     * 如实标记，notice 也向模型说明了截断前后的规模。
     */
    fun guard(text: String, maxTokens: Int = DEFAULT_MAX_OUTPUT_TOKENS): GuardedResult {
        if (text.isEmpty()) return GuardedResult(text, 0, truncated = false)
        val estimated = estimateTokens(text)
        if (estimated <= maxTokens) {
            return GuardedResult(text, estimated, truncated = false)
        }
        val head = prefixWithinTokenBudget(text, maxTokens)
        // 尾部保留只针对"被截掉"的区间：head 覆盖到文末时无需再补尾。
        val tail = if (head.length < text.length) {
            val tailBudget = maxTokens * TAIL_FRACTION_NUMERATOR / TAIL_FRACTION_DENOMINATOR
            suffixWithinTokenBudget(text.substring(head.length), tailBudget)
        } else {
            ""
        }
        val notice = truncationNotice(maxTokens, estimated)
        val truncatedText = if (tail.isNotEmpty()) head + notice + tail else head + notice
        return GuardedResult(truncatedText, maxTokens, truncated = true)
    }

    /** 头部累计 token 不超过 [budget] 的最长前缀（逐 code point，不切断代理对）。 */
    private fun prefixWithinTokenBudget(text: String, budget: Int): String {
        var used = 0.0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val cost = codePointTokenCost(cp)
            if (used + cost > budget) break
            used += cost
            i += Character.charCount(cp)
        }
        return text.substring(0, i)
    }

    /** 尾部累计 token 不超过 [budget] 的最长后缀（保持原顺序，不切断代理对）。 */
    private fun suffixWithinTokenBudget(text: String, budget: Int): String {
        var used = 0.0
        var end = text.length
        while (end > 0) {
            val cp = text.codePointBefore(end)
            val cost = codePointTokenCost(cp)
            if (used + cost > budget) break
            used += cost
            end -= Character.charCount(cp)
        }
        return text.substring(end)
    }

    /** 与 [ContextTokenEstimator] 一致的逐字符成本：ASCII 字母数字 0.25，空白 0，其余（含 CJK）1。 */
    private fun codePointTokenCost(cp: Int): Double = when {
        cp <= 0x7f && cp.toChar().isLetterOrDigit() -> 0.25
        Character.isWhitespace(cp) -> 0.0
        else -> 1.0
    }

    private fun truncationNotice(maxTokens: Int, estimatedTokens: Int): String =
        "\n\n[OUTPUT TRUNCATED - exceeded $maxTokens token limit]\n\n" +
            "The MCP tool output was truncated from ~$estimatedTokens estimated tokens to $maxTokens. " +
            "If this MCP server provides pagination or filtering tools, use them to retrieve " +
            "specific portions of the data. Otherwise, inform the user that you are working with " +
            "truncated output and results may be incomplete."
}
