package com.lxseek.chat.util

import java.util.concurrent.ConcurrentHashMap

/**
 * A minimal TTL (time-to-live) in-memory cache, adapted from the async cache pattern of
 * MediaCrawler's `ExpiringLocalCache` (AbstractCache + CacheFactory). Kept dependency-free,
 * thread-safe and non-coroutine so it can be used on the app's synchronous hot paths (e.g. web
 * search). Entries expire lazily on [get], with an opportunistic sweep on [set] so memory stays
 * bounded without a background thread.
 */
interface TtlCache<K, V> {
    /** Store [value] under [key], expiring [ttlMillis] from now. A non-positive TTL removes the key. */
    fun set(key: K, value: V, ttlMillis: Long)

    /** Return the value if present and not expired, otherwise null. */
    fun get(key: K): V?

    /** Drop all entries. */
    fun clear()

    /** Approximate number of live entries. */
    val size: Int
}

/**
 * Default [TtlCache] backed by a [ConcurrentHashMap].
 *
 * - Writes are atomic and safe from any thread.
 * - [get] removes an expired entry before returning null.
 * - [set] opportunistically sweeps expired entries once the map exceeds [sweepThreshold].
 *   Sweeps are throttled by [sweepMinIntervalMillis] so a cache that stays above the
 *   threshold with mostly-live entries (all writes hitting the cap) does not degrade
 *   into a full-table scan on every single [set] (O(n) per write → O(n²) cumulative).
 */
class InMemoryTtlCache<K, V>(
    private val sweepThreshold: Int = 256,
    private val sweepMinIntervalMillis: Long = DEFAULT_SWEEP_INTERVAL_MS,
) : TtlCache<K, V> {

    private data class Entry<V>(val value: V, val expireNanos: Long)

    private val map = ConcurrentHashMap<K, Entry<V>>()

    /** 上次成功 sweep 的时刻（毫秒）。仅由 set 的写路径访问，volatile 保证多写线程可见。 */
    @Volatile
    private var lastSweepAt: Long = 0L

    override fun set(key: K, value: V, ttlMillis: Long) {
        if (ttlMillis <= 0) {
            map.remove(key)
            return
        }
        // TTL 溢出保护：极端大的 ttlMillis 乘以 1e6 会溢出为负，导致条目"立即过期"；
        // 钳制到安全上界（约 292 年，等价于 Long.MAX_VALUE 纳秒）。
        val safeTtl = ttlMillis.coerceAtMost(MAX_TTL_MILLIS)
        map[key] = Entry(value, System.nanoTime() + safeTtl * NANOS_PER_MILLI)
        if (map.size >= sweepThreshold) {
            maybeSweep()
        }
    }

    override fun get(key: K): V? {
        val entry = map[key] ?: return null
        if (entry.expireNanos <= System.nanoTime()) {
            map.remove(key, entry)
            return null
        }
        return entry.value
    }

    override fun clear() = map.clear()

    override val size: Int get() = map.size

    /**
     * 时间节流的过期清扫。两个并发写线程同时满足阈值时，只有抢到间隔窗口的那个
     * 真正扫描；另一个线程只做一次 volatile 读后直接返回，把均摊开销压回 O(1)。
     */
    private fun maybeSweep() {
        val now = System.currentTimeMillis()
        if (now - lastSweepAt < sweepMinIntervalMillis) return
        synchronized(this) {
            // 双重检查：排队等锁期间可能已有其他线程完成清扫。
            if (now - lastSweepAt < sweepMinIntervalMillis) return
            lastSweepAt = now
            val nowNanos = System.nanoTime()
            map.entries.removeIf { (_, entry) -> entry.expireNanos <= nowNanos }
        }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L

        /** 默认清扫节流间隔（毫秒）：同一窗口内最多做一次全表扫描。 */
        const val DEFAULT_SWEEP_INTERVAL_MS = 1_000L

        /** TTL 安全上界（毫秒）：再大会令纳秒时间戳算术溢出。 */
        const val MAX_TTL_MILLIS = Long.MAX_VALUE / NANOS_PER_MILLI
    }
}