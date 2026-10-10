package com.s0undplus.twitch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

class TtlCacheTest {
    private var now = 0L
    private var loads = 0
    private val cache = TtlCache<String, String>(maxEntries = 2, ttlMs = 1_000, nowMs = { now })

    private suspend fun get(key: String, cacheable: Boolean = true) = cache.get(key) {
        loads++
        "$key#$loads" to cacheable
    }

    @Test
    fun aFreshEntryIsServedWithoutLoadingAgain() = runTest {
        assertEquals("a#1", get("a"))
        now = 999
        assertEquals("a#1", get("a"))
        assertEquals(1, loads)
    }

    @Test
    fun anExpiredEntryIsLoadedAgain() = runTest {
        get("a")
        now = 1_000
        assertEquals("a#2", get("a"))
        assertEquals(2, loads)
    }

    @Test
    fun anIncompleteAnswerIsReturnedButNotRemembered() = runTest {
        assertEquals("a#1", get("a", cacheable = false))
        assertEquals("a#2", get("a"))
        assertEquals("a#2", get("a"))
        assertEquals(2, loads)
    }

    @Test
    fun anExpiredEntryIsDroppedWhenItsReplacementIsIncomplete() = runTest {
        get("a")
        now = 5_000
        get("a", cacheable = false)
        assertEquals("a#3", get("a"))
    }

    @Test
    fun theLeastRecentlyUsedEntryIsEvictedBeyondTheLimit() = runTest {
        get("a")
        get("b")
        get("a") // touch: b is now the least recently used
        get("c") // evicts b
        loads = 0
        get("a")
        get("c")
        assertEquals(0, loads, "a and c are still cached")
        get("b")
        assertEquals(1, loads, "b was evicted")
    }

    @Test
    fun aFailedLoadStoresNothingAndTheNextCallTriesAgain() = runTest {
        assertFailsWith<IllegalStateException> { cache.get("a") { error("boom") } }
        assertEquals("a#1", get("a"))
    }

    @Test
    fun concurrentCallersForTheSameKeyShareOneLoad() = runTest {
        var started = 0
        val results = (1..5).map {
            async {
                cache.get("a") {
                    started++
                    delay(100)
                    "value" to true
                }
            }
        }.awaitAll()
        assertEquals(List(5) { "value" }, results)
        assertEquals(1, started)
    }
}
