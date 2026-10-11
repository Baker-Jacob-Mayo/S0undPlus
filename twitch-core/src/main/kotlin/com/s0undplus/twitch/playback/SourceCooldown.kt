package com.s0undplus.twitch.playback

/**
 * Remembers which ad-free sources (proxies, token identities) failed recently so they are skipped for [windowMs] and
 * then given another chance. A source that failed once is not written off for the whole session, and a source that
 * failed a moment ago is not retried at the very next opportunity. Not thread-safe: use from one thread (the UI one).
 */
class SourceCooldown<K : Any>(
    private val windowMs: Long,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val failedAt = HashMap<K, Long>()

    fun failed(key: K) {
        failedAt[key] = nowMs()
    }

    fun isCoolingDown(key: K): Boolean = failedAt[key]?.let { nowMs() - it < windowMs } ?: false

    /** [items] without the ones that failed within the window, in their original order. */
    fun <T> available(items: List<T>, key: (T) -> K): List<T> = items.filterNot { isCoolingDown(key(it)) }

    fun clear() = failedAt.clear()
}
