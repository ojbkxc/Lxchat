package com.lxseek.chat.im

import kotlinx.coroutines.delay

/**
 * Sends a long reply as several short IM messages. The text is split on sentence/line
 * boundaries whenever possible so every segment reads naturally, and segments are sent
 * with a small delay between them to mimic human typing / avoid gateway rate limits.
 *
 * Used by [ImToolProvider.im_send_multi] and by the automatic-reply loop when the agent's
 * reply is too long for a single message.
 */
class MultiSegmentMessageSender(
    private val maxSegmentLength: Int = DEFAULT_MAX_SEGMENT_LENGTH,
    private val defaultDelayMs: Long = DEFAULT_DELAY_MS,
) {
    /**
     * Sends [text] as one or more messages. When it fits in a single segment only one
     * message is sent (no artificial delay). Returns one [ImSendResult] per message, in
     * order, so callers can inspect per-segment success.
     */
    suspend fun send(
        channel: MessageChannel,
        conversationId: String,
        text: String,
        delayMs: Long = defaultDelayMs,
    ): List<ImSendResult> {
        val segments = split(text)
        if (segments.isEmpty()) return emptyList()
        if (segments.size == 1) {
            return listOf(channel.sendMessage(conversationId, segments.first()))
        }
        val results = ArrayList<ImSendResult>(segments.size)
        segments.forEachIndexed { index, segment ->
            if (index > 0) delay(delayMs.coerceAtLeast(0L))
            results.add(channel.sendMessage(conversationId, segment))
        }
        return results
    }

    /**
     * Splits [text] into segments of at most [maxSegmentLength] characters, preferring to
     * break at newlines first, then at sentence-ending punctuation, so segments are not
     * cut mid-sentence when possible.
     */
    fun split(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed.length <= maxSegmentLength) return listOf(trimmed)

        val result = ArrayList<String>()
        var start = 0
        while (start < trimmed.length) {
            val end = minOf(start + maxSegmentLength, trimmed.length)
            if (end == trimmed.length) {
                result.add(trimmed.substring(start))
                break
            }
            val window = trimmed.substring(start, end)
            val breakAt = lastBreakIndex(window)
            val cut = if (breakAt > 0) breakAt else maxSegmentLength
            result.add(window.substring(0, cut).trimEnd())
            start += cut
        }
        return result
    }

    /** Returns the index (exclusive) of the best natural break inside [s], or -1 if none. */
    private fun lastBreakIndex(s: String): Int {
        val newline = s.lastIndexOf('\n')
        if (newline > 0) return newline + 1
        for (ender in SENTENCE_ENDERS) {
            val idx = s.lastIndexOf(ender)
            if (idx > 0) return idx + 1
        }
        return -1
    }

    companion object {
        const val DEFAULT_MAX_SEGMENT_LENGTH = 1800
        const val DEFAULT_DELAY_MS = 800L

        private val SENTENCE_ENDERS = charArrayOf(
            '。', '！', '？', '！', '…',
            '.', '!', '?',
            '；', ';',
            '）', ')',
        )
    }
}
