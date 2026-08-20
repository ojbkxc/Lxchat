package com.lxseek.chat.api

import kotlinx.coroutines.sync.Mutex

/**
 * Process-wide serialization gate for every on-device model operation — local chat
 * generation AND embedding computation.
 *
 * Replaces the former global [com.lxseek.chat.automation.GenerationQueue] single
 * slot for the local path. The queue serialized ALL generation (remote included),
 * which needlessly blocked remote parallelism; only local model work actually needs
 * to be mutual-excluded, because a chat model held resident by [LocalProvider] plus a
 * concurrently-loaded embedding model (see [LlamaEngine.computeEmbeddings], which
 * loads+frees a model per call) can exceed the native heap and OOM the process.
 *
 * Holders:
 *  • [LocalProvider.generateResponse] wraps each native generation turn.
 *  • [LlamaEngine.computeEmbeddings] wraps its load→compute→free cycle.
 *  • [com.lxseek.chat.viewmodel.MessageGenerationController.generateTitle] wraps
 *    the local title-generation turn.
 *
 * [Mutex.withLock] is cancellable, so a Stop releases the slot immediately and the
 * next local generation can proceed without waiting for the cancelled one to unwind.
 *
 * INVARIANT: local models never emit tool calls, so each local generation acquires
 * the mutex exactly once (no release/re-acquire between tool rounds). If local
 * tool-calling is ever added, holders must be re-audited — an inter-round release
 * would let another conversation's model load interleave mid-generation.
 */
object LocalModelSerializer {
    val mutex: Mutex = Mutex()
}
