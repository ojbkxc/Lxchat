package com.lxseek.chat.mcp

import com.lxseek.chat.api.util.ContextTokenEstimator

/**
 * Guards MCP tool output against runaway token usage, mirroring cc-haha's
 * mcpValidation.ts. Oversized text/JSON results are truncated to the budget and
 * annotated so the model knows the data was cut instead of silently reasoning
 * over a partial payload.
 *
 * The estimate is deterministic ([ContextTokenEstimator.estimateText]) rather than
 * a remote token-count API call, keeping the guard free and offline-friendly.
 */
object McpOutputGuard {
    /** Matches cc-haha's DEFAULT_MAX_MCP_OUTPUT_TOKENS. */
    const val DEFAULT_MAX_OUTPUT_TOKENS = 25_000

    /** ~4 chars/token, matching ContextTokenEstimator's conservative ASCII ratio. */
    private const val CHARS_PER_TOKEN = 4

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
     */
    fun guard(text: String, maxTokens: Int = DEFAULT_MAX_OUTPUT_TOKENS): GuardedResult {
        if (text.isEmpty()) return GuardedResult(text, 0, truncated = false)
        val estimated = estimateTokens(text)
        if (estimated <= maxTokens) {
            return GuardedResult(text, estimated, truncated = false)
        }
        val truncated =
            truncateToSafeChars(text, maxTokens * CHARS_PER_TOKEN) +
                truncationNotice(maxTokens, estimated)
        return GuardedResult(truncated, maxTokens, truncated = true)
    }

    /** UTF-16 safe truncation: never splits a surrogate pair (emoji etc.). */
    private fun truncateToSafeChars(content: String, maxChars: Int): String {
        if (content.length <= maxChars) return content
        var end = maxChars
        if (content[end - 1].isHighSurrogate() && end < content.length) {
            end--
        }
        return content.substring(0, end)
    }

    private fun truncationNotice(maxTokens: Int, estimatedTokens: Int): String =
        "\n\n[OUTPUT TRUNCATED - exceeded $maxTokens token limit]\n\n" +
            "The MCP tool output was truncated from ~$estimatedTokens estimated tokens to $maxTokens. " +
            "If this MCP server provides pagination or filtering tools, use them to retrieve " +
            "specific portions of the data. Otherwise, inform the user that you are working with " +
            "truncated output and results may be incomplete."
}
