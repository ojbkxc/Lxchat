package com.lxseek.chat.viewmodel

import com.lxseek.chat.util.TtsManager

/**
 * Feeds the streaming assistant reply into [TtsManager.speakQueued] sentence-by-sentence, so
 * read-aloud starts while generation is still running instead of waiting for the full text
 * to commit. Tracks how much of the message was already queued; on stream end only the
 * not-yet-spoken tail is appended.
 *
 * 并发（M5）：onStreamText/onStreamEnd 可能从不同协程交错调用，共享的游标与围栏
 * 状态统一在锁内维护（synchronized(this)），消除游标竞态导致的漏读/重读。
 * Markdown（跨段切断）：``` 围栏代码块内的换行不算句读，代码块整体保持完整，
 * 在块闭合或流结束时一次性入队，避免代码被 \n 逐行切碎朗读。
 */
internal class StreamingTtsSpeaker(
    private val language: () -> String,
    private val rate: () -> Float,
) {
    private var messageId: String? = null
    private var spokenUpTo = 0

    /** 是否处于 ``` 围栏代码块内（流式计数：奇数次出现视为开、偶数次视为关）。 */
    private var inFencedCode = false

    /** Id of the last message for which at least one chunk was actually queued. */
    @Volatile var spokeFor: String? = null
        private set

    @Synchronized
    fun onStreamText(id: String, rawText: String) {
        if (messageId != id) {
            messageId = id
            spokenUpTo = 0
            inFencedCode = false
        }
        if (rawText.length < spokenUpTo) {
            spokenUpTo = rawText.length
            inFencedCode = false
        }
        var i = spokenUpTo
        while (i < rawText.length) {
            // 围栏边界：进入代码块前先把块外的正文按句切出去；块内一律不切分。
            if (rawText.startsWith(CODE_FENCE, i)) {
                if (!inFencedCode && i > spokenUpTo) {
                    enqueue(rawText.substring(spokenUpTo, i))
                    spokenUpTo = i
                }
                inFencedCode = !inFencedCode
                i += CODE_FENCE.length
                continue
            }
            if (inFencedCode) {
                i++
                continue
            }
            val c = rawText[i]
            val isEnder = c in SENTENCE_ENDERS ||
                (c == '.' && (i + 1 >= rawText.length || rawText[i + 1] == ' '))
            val isPause = c in PAUSE_MARKS
            if (isEnder || (isPause && i - spokenUpTo >= MIN_PAUSE_SEGMENT)) {
                enqueue(rawText.substring(spokenUpTo, i + 1))
                spokenUpTo = i + 1
            }
            i++
        }
    }

    /** After the stream commits, speak only the tail that was never queued mid-stream. */
    @Synchronized
    fun onStreamEnd(id: String, rawText: String) {
        if (messageId != id) {
            messageId = id
            spokenUpTo = 0
            inFencedCode = false
        }
        if (rawText.length < spokenUpTo) {
            spokenUpTo = rawText.length
            inFencedCode = false
        }
        if (spokenUpTo < rawText.length) {
            enqueue(rawText.substring(spokenUpTo))
            spokenUpTo = rawText.length
        }
        inFencedCode = false
    }

    private fun enqueue(segment: String) {
        val plain = TtsManager.stripMarkdown(segment)
        if (plain.isBlank()) return
        if (TtsManager.speakQueued(plain, language(), rate())) {
            spokeFor = messageId
        }
    }

    companion object {
        private const val MIN_PAUSE_SEGMENT = 24
        private const val SENTENCE_ENDERS = "。！？!?；;\n"
        private const val PAUSE_MARKS = "，,、：:"
        private const val CODE_FENCE = "```"
    }
}
