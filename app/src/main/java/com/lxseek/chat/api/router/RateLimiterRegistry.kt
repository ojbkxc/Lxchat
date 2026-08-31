package com.lxseek.chat.api.router

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Semaphore

/**
 * 速率限制与并发限制注册表
 *
 * 进程级单例注册表，按逻辑键缓存 [SlidingWindowRateLimiter] 和并发 [Semaphore]。
 * 当配置的限额发生变化时，旧实例会被替换为新配置的实例。
 *
 * 设计意图：
 * - 同一 provider/model 的多次请求共享同一限流器，避免每请求各建一份
 * - 线程安全：使用 [ConcurrentHashMap.compute] 原子地 get-or-create
 * - 合并了 HermesApp 的 RateLimiterRegistry 与 RequestConcurrencyRegistry 职责
 */
object RateLimiterRegistry {
    private data class RateEntry(
        val maxRequestsPerMinute: Int,
        val limiter: SlidingWindowRateLimiter,
    )

    private data class ConcurrencyEntry(
        val maxConcurrent: Int,
        val semaphore: Semaphore,
    )

    private val rateLimiters = ConcurrentHashMap<String, RateEntry>()
    private val concurrencySemaphores = ConcurrentHashMap<String, ConcurrencyEntry>()

    /**
     * 获取或创建指定键的滑动窗口速率限制器。
     *
     * @param key 逻辑键，通常为 `providerName` 或 `providerName:modelId`
     * @param maxRequestsPerMinute 每分钟最大请求数，必须 > 0
     */
    fun getOrCreateRateLimiter(key: String, maxRequestsPerMinute: Int): SlidingWindowRateLimiter {
        require(maxRequestsPerMinute > 0) { "maxRequestsPerMinute must be > 0" }

        return checkNotNull(
            rateLimiters.compute(key) { _, existing ->
                if (existing == null || existing.maxRequestsPerMinute != maxRequestsPerMinute) {
                    RateEntry(
                        maxRequestsPerMinute = maxRequestsPerMinute,
                        limiter = SlidingWindowRateLimiter(maxRequestsPerMinute = maxRequestsPerMinute),
                    )
                } else {
                    existing
                }
            }
        ).limiter
    }

    /**
     * 获取或创建指定键的并发信号量。
     *
     * @param key 逻辑键，通常为 `providerName` 或 `providerName:modelId`
     * @param maxConcurrent 最大并发请求数，必须 > 0
     */
    fun getOrCreateConcurrencySemaphore(key: String, maxConcurrent: Int): Semaphore {
        require(maxConcurrent > 0) { "maxConcurrent must be > 0" }

        return checkNotNull(
            concurrencySemaphores.compute(key) { _, existing ->
                if (existing == null || existing.maxConcurrent != maxConcurrent) {
                    ConcurrencyEntry(
                        maxConcurrent = maxConcurrent,
                        semaphore = Semaphore(maxConcurrent),
                    )
                } else {
                    existing
                }
            }
        ).semaphore
    }

    /** 清除所有缓存的限流器与信号量（主要用于测试）。 */
    fun clear() {
        rateLimiters.clear()
        concurrencySemaphores.clear()
    }
}