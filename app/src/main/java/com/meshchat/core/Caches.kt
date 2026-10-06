package com.meshchat.core

/** Time-bounded LRU set keyed by "src:msgId". Stops duplicates and relay loops. */
class DuplicateCache(
    private val capacity: Int = 4000,
    private val ttlMs: Long = 10 * 60_000L,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val map = object : LinkedHashMap<String, Long>(256, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > capacity
    }

    /** Returns true if [key] was already seen (and still fresh). Otherwise records it and returns false. */
    @Synchronized
    fun checkAndAdd(key: String): Boolean {
        val now = clock()
        val seenAt = map[key]
        if (seenAt != null && now - seenAt <= ttlMs) return true
        map.remove(key)
        map[key] = now
        return false
    }

    @Synchronized
    fun forget(key: String) {
        map.remove(key)
    }

    @Synchronized
    fun size(): Int = map.size
}

/** Per-author token bucket used as the decentralized spam brake for Announce posts. */
class RateLimiter(
    private val capacity: Int = 5,
    private val refillMs: Long = 12_000L,
    private val maxKeys: Int = 1000,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private class Bucket(var tokens: Double, var updated: Long)

    private val buckets = object : LinkedHashMap<String, Bucket>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bucket>?): Boolean = size > maxKeys
    }

    @Synchronized
    fun allow(key: String): Boolean {
        val now = clock()
        val b = buckets.getOrPut(key) { Bucket(capacity.toDouble(), now) }
        b.tokens = minOf(capacity.toDouble(), b.tokens + (now - b.updated).toDouble() / refillMs)
        b.updated = now
        if (b.tokens >= 1.0) {
            b.tokens -= 1.0
            return true
        }
        return false
    }
}
