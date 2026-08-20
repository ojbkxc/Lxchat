package com.lxseek.chat.api.util

import com.lxseek.chat.api.GenerationError
import com.lxseek.chat.api.StreamEvent
import com.lxseek.chat.api.openai.StreamingTextToolCallParser

/**
 * Provider-neutral recovery for gateways that serialize a tool call inside ordinary text content.
 *
 * Native tool blocks remain authoritative and bypass this class. Text is only intercepted while
 * tools were actually offered in the request, so a normal answer containing JSON/XML examples is
 * not globally reinterpreted as executable behavior. The streaming parser withholds possible tag
 * prefixes, preventing split `<invoke>` markup from flashing into the assistant answer before the
 * closing tag arrives.
 */
internal class TextToolCallRecovery(enabled: Boolean) {
    private val parser = StreamingTextToolCallParser().takeIf { enabled }

    var emittedToolCall: Boolean = false
        private set

    suspend fun route(event: StreamEvent, emit: suspend (StreamEvent) -> Unit) {
        val active = parser
        if (active == null || event !is StreamEvent.TextChunk) {
            emit(event)
            return
        }
        active.feed(
            content = event.text,
            onText = { emit(StreamEvent.TextChunk(it)) },
            onUpdate = { snapshot ->
                emit(
                    StreamEvent.ToolCallUpdate(
                        streamKey = snapshot.streamKey,
                        id = null,
                        name = snapshot.name,
                        arguments = snapshot.arguments,
                    )
                )
            },
            onComplete = { call ->
                emittedToolCall = true
                emit(
                    StreamEvent.ToolCallRequest(
                        id = "text_tool_${java.util.UUID.randomUUID()}",
                        name = call.name,
                        arguments = call.arguments,
                        streamKey = call.streamKey,
                    )
                )
            },
            onMalformed = { cause ->
                emit(
                    StreamEvent.Error(
                        GenerationError.SseParse(
                            rawLine = "text_tool_call",
                            cause = cause,
                        )
                    )
                )
            },
        )
    }

    suspend fun finish(emit: suspend (StreamEvent) -> Unit) {
        val active = parser ?: return
        active.flush(
            onText = { emit(StreamEvent.TextChunk(it)) },
            onUpdate = { snapshot ->
                emit(
                    StreamEvent.ToolCallUpdate(
                        streamKey = snapshot.streamKey,
                        id = null,
                        name = snapshot.name,
                        arguments = snapshot.arguments,
                    )
                )
            },
            onComplete = { call ->
                emittedToolCall = true
                emit(
                    StreamEvent.ToolCallRequest(
                        id = "text_tool_${java.util.UUID.randomUUID()}",
                        name = call.name,
                        arguments = call.arguments,
                        streamKey = call.streamKey,
                    )
                )
            },
            onMalformed = { cause ->
                emit(
                    StreamEvent.Error(
                        GenerationError.SseParse(
                            rawLine = "text_tool_call",
                            cause = cause,
                        )
                    )
                )
            },
        )
    }
}
