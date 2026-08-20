package com.lxseek.chat.data

import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide per-model locks serializing embedding-cache runs.
 *
 * Two runners exist for the same work: RagManager's in-app coroutine (fast path with
 * progress UI) and EmbeddingCacheWorker (the WorkManager continuation that survives
 * process death). Both run in this process and both take this lock, so they can never
 * compute the same batches concurrently — whichever runs second finds the messages
 * already embedded and no-ops. The in-app runner additionally acquires the lock
 * BEFORE enqueueing the worker, so the worker only does real work when the process
 * died mid-cache and WorkManager restarted it in a fresh process.
 */
object EmbeddingCacheLocks {
    private val mutexes = ConcurrentHashMap<String, Mutex>()

    fun forModel(modelId: String): Mutex = mutexes.computeIfAbsent(modelId) { Mutex() }

    /** Drops a deleted model's lock entry. Callers must not hold the lock. */
    fun remove(modelId: String) {
        mutexes.remove(modelId)
    }
}
