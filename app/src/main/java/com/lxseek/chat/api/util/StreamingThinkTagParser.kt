package com.lxseek.chat.api.util

/**
 * Compatibility facade used by OpenAI-compatible providers. Parsing is owned by the same
 * incremental lexer as local/Ollama generation so chunking and tag behavior cannot drift.
 */
class StreamingThinkTagParser {
    private val parser = IncrementalThinkingParser()

    val inThinkingBlock: Boolean get() = parser.inThinking
    val pendingBuffer: String get() = parser.pendingContent

    suspend fun feed(
        content: String,
        thinkingEnabled: Boolean,
        onText: suspend (String) -> Unit,
        onThought: suspend (String) -> Unit,
    ) = parser.feed(content, thinkingEnabled, onText, onThought)

    suspend fun flush(
        onText: suspend (String) -> Unit,
        onThought: suspend (String) -> Unit,
        thinkingEnabled: Boolean? = null,
    ) {
        if (thinkingEnabled == null) {
            parser.flush(onText = onText, onThought = onThought)
        } else {
            parser.flush(thinkingEnabled, onText, onThought)
        }
    }
}
