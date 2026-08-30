package com.lxseek.chat.viewmodel

import com.lxseek.chat.util.TtsManager

/**
 * Feeds the streaming assistant reply into [TtsManager.speakQueued] sentence-by-sentence, so
 * read-aloud starts while generation is still running instead of waiting for the full text
 * to commit. Tracks how much of the message was already queued; on stream end only the
 * not-yet-spoken tail is appended.
 */
internal class StreamingTtsSpeaker(
    private val language: () -> String,
    private val rate: () -> Float,
) {
    private var messageId: String? = null
    private var spokenUpTo = 0

    /** Id of the last message for which at least one chunk was actually queued. */
    @Volatile var spokeFor: String? = null
        private set

    fun onStreamText(id: String, rawText: String) {
        if (messageId != id) {
            messageId = id
            spokenUpTo = 0
        }
        if (rawText.length < spokenUpTo) spokenUpTo = rawText.length
        var i = spokenUpTo
        while (i < rawText.length) {
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
    fun onStreamEnd(id: String, rawText: String) {
        if (messageId != id) {
            messageId = id
            spokenUpTo = 0
        }
        if (rawText.length < spokenUpTo) spokenUpTo = rawText.length
        if (spokenUpTo < rawText.length) {
            enqueue(rawText.substring(spokenUpTo))
            spokenUpTo = rawText.length
        }
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
    }
}
