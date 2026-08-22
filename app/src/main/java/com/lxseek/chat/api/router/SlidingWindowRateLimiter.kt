package com.lxseek.chat.api.router

import java.util.ArrayDeque
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 滑动窗口速率限制器
 *
 * 在固定时间窗口（默认 60 秒）内限制最大请求数量。当窗口内请求达到上限时，
 * 调用方将被挂起直到最早的请求时间戳滑出窗口。
 *
 * 设计意图：
 * - 防止超过 API 提供商的调用频率限制（429 Too Many Requests）
 * - 纯 Kotlin + Coroutines 实现，不引入外部依赖
 * - 线程安全：使用 [Mutex] 保护时间戳队列
 *
 * 算法：维护一个时间戳的 [ArrayDeque]，每次获取时先淘汰窗口外的旧时间戳，
 * 若队列长度仍达上限则返回需要等待的剩余时间，否则记录当前时间戳并立即放行。
 *
 * @param maxRequestsPerMinute 窗口内允许的最大请求数，<=0 表示不限制
 * @param windowMs 窗口大小（毫秒），默认 60 秒
 */
class SlidingWindowRateLimiter(
    val maxRequestsPerMinute: Int,
    private val windowMs: Long = 60_000L,
) {
    private val mutex = Mutex()
    private val timestamps = ArrayDeque<Long>()

    /**
     * 尝试获取一个请求配额。
     *
     * @param nowMs 当前时间戳（毫秒），可注入用于测试
     * @return 需要等待的毫秒数；0 表示已获取配额可立即放行
     */
    suspend fun tryAcquire(nowMs: Long = System.currentTimeMillis()): Long {
        if (maxRequestsPerMinute <= 0) return 0L

        return mutex.withLock {
            // 淘汰已滑出窗口的时间戳
            while (timestamps.isNotEmpty() && nowMs - timestamps.first() >= windowMs) {
                timestamps.removeFirst()
            }

            if (timestamps.size >= maxRequestsPerMinute) {
                // 窗口已满，计算到最早时间戳滑出窗口的剩余等待时间
                val oldest = timestamps.first()
                (windowMs - (nowMs - oldest)).coerceAtLeast(1L)
            } else {
                // 记入本次请求，立即放行
                timestamps.addLast(nowMs)
                0L
            }
        }
    }

    /**
     * 阻塞式获取配额：循环尝试直到成功，期间用 [delay] 挂起协程。
     *
     * 相比 [tryAcquire]，此方法适合在请求发起前直接调用——调用返回时
     * 一定已获得配额。
     */
    suspend fun acquire() {
        while (true) {
            val retryAfterMs = tryAcquire()
            if (retryAfterMs <= 0L) {
                return
            }
            delay(retryAfterMs)
        }
    }
}