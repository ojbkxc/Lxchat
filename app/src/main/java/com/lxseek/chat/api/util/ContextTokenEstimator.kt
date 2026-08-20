package com.lxseek.chat.api.util

import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.util.Constants
import kotlin.math.ceil

/**
 * Deterministic cross-provider estimate of provider-visible conversation tokens.
 *
 * Exact tokenization is model-specific and unavailable offline for arbitrary custom providers.
 * This estimator intentionally leans conservative: ASCII word-like runs use roughly four
 * characters per token, non-ASCII code points and punctuation cost one each, and message/image/
 * tool framing has explicit overhead. Every threshold, rollout decision, and UI indicator uses
 * this same function, so the estimate cannot drift between surfaces.
 */
object ContextTokenEstimator {
    private const val MESSAGE_OVERHEAD = 8
    private const val IMAGE_ESTIMATE = 1_024
    private const val TOOL_CALL_OVERHEAD = 16
    private const val SAFETY_NUMERATOR = 11L
    private const val SAFETY_DENOMINATOR = 10L

    fun estimate(messages: List<ChatMessage>): Int {
        val raw = messages.fold(0L) { total, message ->
            (total + estimateMessageRaw(message)).coerceAtMost(Int.MAX_VALUE.toLong())
        }
        return applySafetyMargin(raw)
    }

    internal fun estimateText(text: String): Int = applySafetyMargin(estimateTextRaw(text))

    private fun estimateMessageRaw(message: ChatMessage): Long {
        var total = MESSAGE_OVERHEAD.toLong() + estimateTextRaw(message.text)
        total += message.images.size.toLong() * IMAGE_ESTIMATE
        if (
            message.id.startsWith(Constants.TOOL_MSG_PREFIX) ||
            message.id.startsWith(Constants.RESULT_MSG_PREFIX)
        ) {
            val segments = message.segments.orEmpty().filter { it.type == "tool" }
            if (segments.isNotEmpty()) {
                segments.forEach { segment ->
                    total += TOOL_CALL_OVERHEAD
                    total += estimateTextRaw(segment.toolName.orEmpty())
                    total += estimateTextRaw(segment.toolArgs.orEmpty())
                    total += estimateTextRaw(segment.toolResult.orEmpty())
                    total += estimateTextRaw(segment.signature.orEmpty())
                }
            } else {
                message.toolCall?.let { call ->
                    total += TOOL_CALL_OVERHEAD
                    total += estimateTextRaw(call.toolName)
                    total += estimateTextRaw(call.arguments)
                    total += estimateTextRaw(call.result)
                    total += estimateTextRaw(call.signature.orEmpty())
                }
            }
        }
        return total
    }

    private fun estimateTextRaw(text: String): Long {
        if (text.isEmpty()) return 0L
        var tokens = 0L
        var asciiRun = 0

        fun flushAsciiRun() {
            if (asciiRun > 0) {
                tokens += ceil(asciiRun / 4.0).toLong()
                asciiRun = 0
            }
        }

        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            when {
                codePoint <= 0x7f && Character.isLetterOrDigit(codePoint) -> asciiRun++
                Character.isWhitespace(codePoint) -> flushAsciiRun()
                else -> {
                    flushAsciiRun()
                    tokens++
                }
            }
            index += Character.charCount(codePoint)
        }
        flushAsciiRun()
        return tokens
    }

    private fun applySafetyMargin(raw: Long): Int =
        ((raw * SAFETY_NUMERATOR + SAFETY_DENOMINATOR - 1) / SAFETY_DENOMINATOR)
            .coerceIn(0L, Int.MAX_VALUE.toLong())
            .toInt()
}
