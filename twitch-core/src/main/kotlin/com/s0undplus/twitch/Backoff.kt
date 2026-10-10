package com.s0undplus.twitch

import kotlin.math.min
import kotlin.random.Random

/** Exponential backoff with jitter, shared by every reconnecting socket. */
internal object Backoff {
    private const val INITIAL_MS = 1_000L
    private const val MAX_MS = 30_000L

    fun millis(attempt: Int, random: Random): Long =
        min(MAX_MS, INITIAL_MS shl min(attempt, 5)) + random.nextLong(0, 500)
}
