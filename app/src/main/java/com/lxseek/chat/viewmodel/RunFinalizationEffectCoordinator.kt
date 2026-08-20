package com.lxseek.chat.viewmodel

import com.lxseek.chat.model.RunEffect
import kotlinx.coroutines.delay

/**
 * Executes one mailbox-authorized normal Run finalization with a small bounded retry policy.
 * Identity and terminal disposition come only from [RunEffect.FinalizeRun]; callers must echo the
 * exact result to the conversation mailbox before treating it as authoritative.
 */
internal class RunFinalizationEffectCoordinator(
    private val retryDelaysMs: List<Long> = listOf(0L, 40L, 120L),
    private val delayForRetry: suspend (Long) -> Unit = { delay(it) },
) {
    init {
        require(retryDelaysMs.isNotEmpty())
        require(retryDelaysMs.first() == 0L)
        require(retryDelaysMs.all { it >= 0L })
    }

    suspend fun execute(
        effect: RunEffect.FinalizeRun,
        persist: suspend (RunEffect.FinalizeRun) -> Boolean,
    ): Result {
        var lastFailure: Exception? = null
        retryDelaysMs.forEachIndexed { attempt, retryDelayMs ->
            if (retryDelayMs > 0L) delayForRetry(retryDelayMs)
            try {
                if (persist(effect)) return Result.Succeeded(attempt + 1)
            } catch (error: Exception) {
                lastFailure = error
            }
        }
        return Result.Failed(retryDelaysMs.size, lastFailure)
    }

    sealed interface Result {
        val attempts: Int

        data class Succeeded(override val attempts: Int) : Result
        data class Failed(
            override val attempts: Int,
            val lastFailure: Exception?,
        ) : Result
    }
}
