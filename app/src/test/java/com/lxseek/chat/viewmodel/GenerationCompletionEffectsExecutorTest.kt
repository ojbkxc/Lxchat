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

        // C3 设计：终态 UI 清理（清流/去 loading）先于索引执行，索引失败也不阻塞 UI 恢复
        assertEquals(
            listOf(
                "clear",
                "loading:false",
                "index:model:answer",
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

        // 索引异常被吞且不阻塞清理；排队发送存在时抑制通知（generationCycleComplete=false）
        assertEquals(
            listOf("clear", "loading:false", "index", "release", "queue", "foreground"),
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
