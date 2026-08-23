package com.lxseek.chat.im

import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Sends a long reply as several short IM messages. The text is split on sentence/line
 * boundaries whenever possible so every segment reads naturally, and segments are sent
 * with a small delay between them to mimic human typing / avoid gateway rate limits.
 *
 * Used by [ImToolProvider.im_send_multi] and by the automatic-reply loop when the agent's
 * reply is too long for a single message.
 *
 * Also provides [sendStreaming], which simulates a streaming reply by sending an initial
 * short message and then editing it as more content becomes available — used by channels
 * that support message editing ([MessageChannel.supportsEdit]). Channels without edit
 * support transparently degrade to a single full-text send.
 */
class MultiSegmentMessageSender(
    private val maxSegmentLength: Int = DEFAULT_MAX_SEGMENT_LENGTH,
    private val defaultDelayMs: Long = DEFAULT_DELAY_MS,
    /** 流式回复时两次 editMessage 之间的节流间隔（毫秒）。 */
    private val streamingUpdateIntervalMs: Long = DEFAULT_STREAMING_UPDATE_INTERVAL_MS,
    /** 流式回复初始消息的最大字符数；超过此长度后开始按 [streamingChunkLength] 增量编辑。 */
    private val streamingInitialLength: Int = DEFAULT_STREAMING_INITIAL_LENGTH,
    /** 流式回复每次 editMessage 增量追加的字符数。 */
    private val streamingChunkLength: Int = DEFAULT_STREAMING_CHUNK_LENGTH,
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
     * 流式发送消息：先发送初始消息，然后随着内容增长编辑该消息。
     *
     * 行为：
     *  - 当 [channel] 不支持编辑（[MessageChannel.supportsEdit] == false）时，退化为一次性
     *    发送完整 [content]，等价于 [send] 单段路径，避免在不支持编辑的平台上发送半截消息。
     *  - 当支持编辑时，先发送 [content] 的前 [streamingInitialLength] 个字符作为初始消息，
     *    拿到 messageId；随后按 [streamingUpdateIntervalMs] 节流间隔，每次追加
     *    [streamingChunkLength] 个字符并调用 [MessageChannel.editMessage] 更新该消息，
     *    直至完整内容呈现完毕。
     *  - 任意一次 editMessage 失败（返回 false 或抛异常）时，停止编辑，把剩余未呈现的内容
     *    作为一条新消息发送出去，保证用户最终能看到完整回复。
     *
     * [onChunk] 在每次内容更新（包括初始发送和每次成功编辑）后被调用，传入当前已呈现的
     * 文本，调用方可据此刷新 UI 或记录日志。回调本身是 suspend 的，会在本协程内顺序执行。
     *
     * 返回最终消息 ID：流式路径返回初始消息的 messageId（编辑失败后追加的新消息不返回
     * ID，因为初始消息仍是该回复的"主"消息）；退化路径返回一次性发送得到的 messageId。
     * 任何发送失败（NotConfigured / Failure）或 [content] 为空时返回空字符串。
     */
    suspend fun sendStreaming(
        channel: MessageChannel,
        conversationId: String,
        content: String,
        onChunk: (suspend (currentText: String) -> Unit)? = null,
    ): String {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return ""

        // ── 退化路径：不支持编辑，一次性发送完整文本 ──────────────────
        if (!channel.supportsEdit) {
            val result = channel.sendMessage(conversationId, trimmed)
            return when (result) {
                is ImSendResult.Success -> {
                    onChunk?.invoke(trimmed)
                    result.messageId
                }
                is ImSendResult.Failure -> {
                    DebugLog.w(
                        "MultiSegmentSender",
                        "streaming degraded send failed: ${result.reason}",
                    )
                    ""
                }
                ImSendResult.NotConfigured -> ""
            }
        }

        // ── 流式路径：先发初始片段，再按节流间隔 editMessage 增量更新 ─────
        val initialEnd = minOf(streamingInitialLength, trimmed.length)
        val initialText = trimmed.substring(0, initialEnd)
        val firstResult = channel.sendMessage(conversationId, initialText)
        val messageId = when (firstResult) {
            is ImSendResult.Success -> firstResult.messageId
            is ImSendResult.Failure -> {
                DebugLog.w(
                    "MultiSegmentSender",
                    "streaming initial send failed: ${firstResult.reason}; falling back to full send",
                )
                // 初始消息都发不出去，再尝试一次完整发送，给调用方一个可用的 messageId。
                return when (val fallback = channel.sendMessage(conversationId, trimmed)) {
                    is ImSendResult.Success -> {
                        onChunk?.invoke(trimmed)
                        fallback.messageId
                    }
                    else -> ""
                }
            }
            ImSendResult.NotConfigured -> return ""
        }
        onChunk?.invoke(initialText)
        if (initialEnd == trimmed.length) return messageId

        // ── 增量编辑 ───────────────────────────────────────────────
        var pos = initialEnd
        while (pos < trimmed.length) {
            delay(streamingUpdateIntervalMs.coerceAtLeast(0L))
            pos = minOf(pos + streamingChunkLength, trimmed.length)
            val currentText = trimmed.substring(0, pos)
            val edited = try {
                channel.editMessage(conversationId, messageId, currentText)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLog.w("MultiSegmentSender", "editMessage threw, falling back to new message", e)
                false
            }
            if (!edited) {
                DebugLog.w(
                    "MultiSegmentSender",
                    "editMessage returned false at pos=$pos; sending remainder as new message",
                )
                val remainder = trimmed.substring(pos)
                if (remainder.isNotBlank()) {
                    channel.sendMessage(conversationId, remainder)
                }
                onChunk?.invoke(trimmed)
                return messageId
            }
            onChunk?.invoke(currentText)
        }
        return messageId
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

        /** 流式回复默认节流间隔：800ms，与 [DEFAULT_DELAY_MS] 对齐，避免过快刷屏。 */
        const val DEFAULT_STREAMING_UPDATE_INTERVAL_MS = 800L

        /** 流式回复初始消息默认长度：80 字符，足以容纳一句开场白而不显突兀。 */
        const val DEFAULT_STREAMING_INITIAL_LENGTH = 80

        /** 流式回复每次增量追加默认长度：120 字符，约 2-3 个短句，视觉上像在实时打字。 */
        const val DEFAULT_STREAMING_CHUNK_LENGTH = 120

        private val SENTENCE_ENDERS = charArrayOf(
            '。', '！', '？', '！', '…',
            '.', '!', '?',
            '；', ';',
            '）', ')',
        )
    }
}
