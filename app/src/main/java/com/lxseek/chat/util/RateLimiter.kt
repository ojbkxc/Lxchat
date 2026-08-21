package com.lxseek.chat.util

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * A sliding-window rate limiter for per-conversation message throttling.
 *
 * Borrows the token-bucket idea from AstrBot's RateLimitStage but keeps the
 * implementation dependency-free (no extra libraries).
 *
 * Uses a token bucket so bursts are allowed up to [capacity],
 * then refills at [refillPerSecond] tokens/sec. A token represents one
 * permitted message; [tryAcquire] returns false when the bucket is empty.
 *
 * Thread-safe via a single ConcurrentHashMap of per-key buckets.
 */
class RateLimiter(
    private val capacity: Int = 20,
    private val refillPerSecond: Double = 2.0,
) {
    private data class Bucket(var tokens: Double, var lastRefillNanos: Long)

    private val buckets = ConcurrentHashMap<String, Bucket>()

    /** Returns true if a token was acquired (allowed), false if rate-limited. */
    fun tryAcquire(key: String, nowNanos: Long = System.nanoTime()): Boolean {
        val bucket = buckets.computeIfAbsent(key) { Bucket(capacity.toDouble(), nowNanos) }
        synchronized(bucket) {
            val elapsedSec = max(0, nowNanos - bucket.lastRefillNanos) / 1_000_000_000.0
            bucket.tokens = (bucket.tokens + elapsedSec * refillPerSecond).coerceAtMost(capacity.toDouble())
            bucket.lastRefillNanos = nowNanos
            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0
                return true
            }
            return false
        }
    }

    /** Remaining tokens for a key (for UI display). */
    fun remainingTokens(key: String, nowNanos: Long = System.nanoTime()): Int {
        val bucket = buckets[key] ?: return capacity
        synchronized(bucket) {
            val elapsedSec = max(0, nowNanos - bucket.lastRefillNanos) / 1_000_000_000.0
            return ((bucket.tokens + elapsedSec * refillPerSecond).coerceAtMost(capacity.toDouble())).toInt()
        }
    }

    /** Reset the bucket for a key (e.g. when a conversation is deleted). */
    fun reset(key: String) { buckets.remove(key) }

    /** Clear all buckets. */
    fun clear() { buckets.clear() }
}
