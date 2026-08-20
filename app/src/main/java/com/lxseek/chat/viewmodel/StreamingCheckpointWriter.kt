package com.lxseek.chat.viewmodel

import com.lxseek.chat.model.ChatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Single-writer, latest-value-wins persistence lane for streaming checkpoints.
 *
 * Ordinary token updates only replace [requested]; they never wait for Room and cannot build an
 * unbounded queue. A lifecycle boundary calls [flush], which waits until that snapshot or a newer
 * one is durable. [cancelAndJoin] is the terminal-write fence: no stale checkpoint can complete
 * after the final SUCCESS/ERROR/STOPPED transaction.
 */
internal class StreamingCheckpointWriter(
    scope: CoroutineScope,
    private val persist: suspend (ChatMessage) -> Boolean,
    private val onFailure: (Exception) -> Unit,
) {
    private data class Request(val sequence: Long, val message: ChatMessage)
    private data class Completion(val sequence: Long, val targetExists: Boolean)

    private val accepting = AtomicBoolean(true)
    private val nextSequence = AtomicLong(0L)
    private val requested = MutableStateFlow<Request?>(null)
    private val completed = MutableStateFlow(Completion(0L, targetExists = true))

    private val writerJob = scope.launch(Dispatchers.IO) {
        requested.filterNotNull().collect { request ->
            val previous = completed.value
            if (request.sequence <= previous.sequence) return@collect
            if (!previous.targetExists) {
                completed.value = Completion(request.sequence, targetExists = false)
                return@collect
            }
            val targetExists = try {
                persist(request.message)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A checkpoint remains best-effort. Keep the target eligible so a later snapshot
                // can retry, but complete this sequence so a forced boundary cannot deadlock.
                onFailure(e)
                true
            }
            completed.value = Completion(request.sequence, targetExists)
        }
    }

    fun enqueue(message: ChatMessage): Long? {
        if (!accepting.get() || !completed.value.targetExists) return null
        val sequence = nextSequence.incrementAndGet()
        requested.value = Request(sequence, message)
        return sequence
    }

    suspend fun flush(message: ChatMessage): Boolean {
        val sequence = enqueue(message) ?: return false
        return completed.first { it.sequence >= sequence }.targetExists
    }

    suspend fun cancelAndJoin() {
        accepting.set(false)
        writerJob.cancelAndJoin()
    }
}

/** Call-scoped owner of checkpoint throttling and the single durable writer lane. */
internal class GenerationStreamingCheckpoints(
    scope: CoroutineScope,
    private val isLatestPersist: () -> Boolean,
    persist: suspend (ChatMessage) -> Boolean,
    onFailure: (Exception) -> Unit,
) {
    private val gate = StreamingCheckpointGate()
    private val writer = StreamingCheckpointWriter(
        scope = scope,
        persist = { message -> isLatestPersist() && persist(message) },
        onFailure = onFailure,
    )

    suspend fun persist(message: ChatMessage, force: Boolean = false) {
        if (!isLatestPersist()) return
        if (!gate.shouldCheckpoint(System.currentTimeMillis(), force)) return
        if (force) writer.flush(message) else writer.enqueue(message)
    }

    suspend fun close() = writer.cancelAndJoin()
}
