package com.s0undplus.twitch

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackoffTest {
    @Test
    fun doublesFromOneSecondThenStopsAtThirtyWithinTheJitterWindow() {
        val random = Random(7)
        val bases = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L)
        bases.forEachIndexed { attempt, base ->
            val ms = Backoff.millis(attempt, random)
            assertTrue(ms in base until base + 500, "attempt $attempt gave $ms, expected $base plus under 500 ms jitter")
        }
    }

    @Test
    fun hugeAttemptCountsDoNotOverflow() {
        val ms = Backoff.millis(Int.MAX_VALUE, Random(1))
        assertTrue(ms in 30_000L until 30_500L, "$ms")
    }

    @Test
    fun jitterIsDeterministicForASeed() {
        assertEquals(Backoff.millis(2, Random(5)), Backoff.millis(2, Random(5)))
    }
}
