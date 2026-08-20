package com.lxseek.chat.viewmodel

import com.lxseek.chat.model.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class GenerationCompletionEffectsExecutorTest {
    @Test
    fun `successful terminal effects preserve cleanup and notification order`() {
        val events = mutableListOf<String>()
        val executor = GenerationCompletionEffectsExecutor(
            isAppInForeground = { events += "foreground"; false },
            releaseForegroundLease = { events += "release:$it" },
            notify = { text, conversationId -> events += "notify:$conversationId:$text" },
        )

        executor.execute(
            request(terminalPersisted = true, foregroundLeaseAcquired = true),
            callbacks(events, hasQueuedSends = { events += "queue"; false }),
        )

        assertEquals(
            listOf(
                "index:model:answer",
                "clear",
                "loading:false",
                "release:model",
                "queue",
                "foreground",
                "notify:conversation:answer",
            ),
            events,
        )
    }

    @Test
    fun `index failure cannot prevent terminal cleanup and queued work suppresses notification`() {
        val events = mutableListOf<String>()
        val executor = GenerationCompletionEffectsExecutor(
            isAppInForeground = { events += "foreground"; false },
            releaseForegroundLease = { events += "release" },
            notify = { _, _ -> events += "notify" },
        )
        val callbacks = GenerationCompletionEffectsCallbacks(
            onMessagePersisted = { _, _ ->
                events += "index"
                throw IllegalStateException("index failure")
            },
            onStreamClear = { events += "clear" },
            onLoadingChange = { events += "loading:$it" },
            hasQueuedSends = { events += "queue"; true },
        )

        executor.execute(
            request(terminalPersisted = true, foregroundLeaseAcquired = true),
            callbacks,
        )

        assertEquals(
            listOf("index", "clear", "loading:false", "release", "queue", "foreground"),
            events,
        )
    }

    private fun request(
        terminalPersisted: Boolean,
        foregroundLeaseAcquired: Boolean,
    ) = GenerationCompletionEffectsRequest(
        terminalPersisted = terminalPersisted,
        status = MessageStatus.SUCCESS,
        interruptedForQueuedSend = false,
        text = "answer",
        conversationId = "conversation",
        modelMessageId = "model",
        foregroundLeaseAcquired = foregroundLeaseAcquired,
    )

    private fun callbacks(
        events: MutableList<String>,
        hasQueuedSends: () -> Boolean,
    ) = GenerationCompletionEffectsCallbacks(
        onMessagePersisted = { id, text -> events += "index:$id:$text" },
        onStreamClear = { events += "clear" },
        onLoadingChange = { events += "loading:$it" },
        hasQueuedSends = hasQueuedSends,
    )
}
