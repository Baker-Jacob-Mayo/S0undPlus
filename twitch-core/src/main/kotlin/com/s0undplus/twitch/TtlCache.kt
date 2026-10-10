package com.s0undplus.twitch

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A small time-limited, least-recently-used cache for things that are fetched over the network and change rarely
 * (emote sets, badge sets). Loads are serialized, so several callers asking for the same missing key produce one
 * request, and the later callers get the stored answer.
 */
internal class TtlCache<K : Any, V : Any>(
    private val maxEntries: Int,
    private val ttlMs: Long,
    private val nowMs: () -> Long,
) {
    private class Entry<V>(val value: V, val storedAtMs: Long)

    private val mutex = Mutex()
    private val entries = LinkedHashMap<K, Entry<V>>(16, 0.75f, true) // access order: the eldest entry is the LRU one

    /**
     * Returns the stored value for [key] while it is fresh, otherwise calls [load], which returns the value and
     * whether it is complete enough to remember (a partial answer is handed back but not stored, so the next call
     * tries again). An exception from [load] propagates and stores nothing.
     */
    suspend fun get(key: K, load: suspend () -> Pair<V, Boolean>): V = mutex.withLock {
        val now = nowMs()
        entries[key]?.let { if (now - it.storedAtMs < ttlMs) return@withLock it.value }
        val (value, cacheable) = load()
        entries.remove(key)
        if (cacheable) {
            entries[key] = Entry(value, nowMs())
            while (entries.size > maxEntries) entries.remove(entries.keys.first())
        }
        value
    }
}
