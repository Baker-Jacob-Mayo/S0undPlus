package com.s0undplus.twitch.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoRefreshTest {
    @Test
    fun normalModeStopsWhenTheChannelIsOffline() {
        val policy = AutoRefreshPolicy(RefreshMode.NORMAL, maxAttempts = 5, intervalMs = 5_000)
        assertEquals(RefreshStep.Offline, policy.next(attempt = 0, online = false))
        assertEquals(RefreshStep.Retry(5_000), policy.next(attempt = 0, online = true))
        assertEquals(RefreshStep.Retry(5_000), policy.next(attempt = 0, online = null)) // check failed: keep trying
    }

    @Test
    fun aggressiveModeRetriesEvenIfTwitchSaysOffline() {
        val policy = AutoRefreshPolicy(RefreshMode.AGGRESSIVE, maxAttempts = 3, intervalMs = 2_000)
        assertEquals(RefreshStep.Retry(2_000), policy.next(0, online = false))
    }

    @Test
    fun givesUpAfterMaxAttempts() {
        val policy = AutoRefreshPolicy(RefreshMode.NORMAL, maxAttempts = 3)
        assertEquals(RefreshStep.Retry(5_000), policy.next(2, online = true))
        assertEquals(RefreshStep.GaveUp, policy.next(3, online = true))
        assertEquals(RefreshStep.GaveUp, AutoRefreshPolicy(RefreshMode.AGGRESSIVE, 1).next(1, online = false))
    }

    @Test
    fun stallDetectorFiresOnlyAfterTheThreshold() {
        val detector = StallDetector(thresholdMs = 10_000)
        assertFalse(detector.isStalled(0))
        detector.onBuffering(1_000)
        detector.onBuffering(5_000) // still the same stall; the first timestamp wins
        assertFalse(detector.isStalled(10_999))
        assertTrue(detector.isStalled(11_000))
        detector.onProgress()
        assertFalse(detector.isStalled(99_000))
    }
}
