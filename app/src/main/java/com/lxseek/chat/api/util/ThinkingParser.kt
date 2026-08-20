package com.lxseek.chat.api.util

/**
 * Unified local/Ollama facade over [IncrementalThinkingParser].
 */
class ThinkingParser {
    private val parser = IncrementalThinkingParser()

    suspend fun feed(
        content: String,
        thinkingEnabled: Boolean,
        onText: suspend (String) -> Unit,
        onThought: suspend (String) -> Unit,
    ) = parser.feed(content, thinkingEnabled, onText, onThought)

    suspend fun flush(
        onText: suspend (String) -> Unit,
        onThought: suspend (String) -> Unit,
    ) = parser.flush(onText = onText, onThought = onThought)

    fun reset() = parser.reset()
}
