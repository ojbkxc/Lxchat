package com.lxseek.chat.util

/**
 * Tool-call / tool-response pairing fixer inspired by AstrBot's
 * ContextTruncator.fix_messages().
 *
 * When context is truncated or compressed, an assistant message containing
 * tool_calls may lose its corresponding tool response, or a tool response
 * may lose its preceding assistant message. The OpenAI Chat Completions API
 * (and Gemini strictly) requires every tool message to be preceded by an
 * assistant message with matching tool_calls, and vice versa.
 *
 * This utility operates on a generic [Message] list so it can be adapted
 * to Lxchat's model without coupling to Compose or ViewModel internals.
 */
object ToolCallPairFixer {

    /** Minimal message representation for the fixer to work on. */
    interface Message {
        val role: String
        val toolCallId: String? // non-null only for role == "tool"
        val toolCalls: List<String>? // non-null only for role == "assistant" with tool_calls
    }

    /**
     * Fix the message list so every tool response is preceded by a matching
     * assistant(tool_calls) and every assistant(tool_calls) is followed by
     * all its tool responses. Orphaned messages are dropped.
     */
    fun fixMessages(messages: List<Message>): List<Message> {
        if (messages.isEmpty()) return messages
        val fixed = mutableListOf<Message>()
        var pendingAssistant: Message? = null
        val pendingTools = mutableListOf<Message>()

        fun flushPendingIfValid() {
            val pa = pendingAssistant
            if (pa != null && pendingTools.isNotEmpty()) {
                fixed.add(pa)
                fixed.addAll(pendingTools)
            }
            pendingAssistant = null
            pendingTools.clear()
        }

        for (msg in messages) {
            when (msg.role) {
                "tool" -> {
                    if (pendingAssistant != null) pendingTools.add(msg)
                    // Isolated tool messages without preceding assistant(tool_calls) are dropped.
                }
                "assistant" -> {
                    flushPendingIfValid()
                    val toolCalls = msg.toolCalls
                    if (toolCalls != null && toolCalls.isNotEmpty()) {
                        pendingAssistant = msg
                    } else {
                        fixed.add(msg)
                    }
                }
                else -> {
                    flushPendingIfValid()
                    fixed.add(msg)
                }
            }
        }
        flushPendingIfValid()
        return fixed
    }
}
