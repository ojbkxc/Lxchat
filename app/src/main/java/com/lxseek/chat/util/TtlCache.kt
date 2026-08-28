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
 */
class InMemoryTtlCache<K, V>(
    private val sweepThreshold: Int = 256,
) : TtlCache<K, V> {

    private data class Entry<V>(val value: V, val expireNanos: Long)

    private val map = ConcurrentHashMap<K, Entry<V>>()

    override fun set(key: K, value: V, ttlMillis: Long) {
        if (ttlMillis <= 0) {
            map.remove(key)
            return
        }
        map[key] = Entry(value, System.nanoTime() + ttlMillis * NANOS_PER_MILLI)
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

    private fun maybeSweep() {
        val now = System.nanoTime()
        map.entries.removeIf { (_, entry) -> entry.expireNanos <= now }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}