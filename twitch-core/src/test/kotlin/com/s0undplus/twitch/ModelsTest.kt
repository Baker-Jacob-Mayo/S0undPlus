package com.s0undplus.twitch

import kotlin.test.Test
import kotlin.test.assertEquals

class ModelsTest {
    @Test
    fun streamStylePlaceholdersAreFilledIn() {
        assertEquals("https://cdn/live-440x248.jpg", "https://cdn/live-{width}x{height}.jpg".sized(440, 248))
    }

    @Test
    fun videoStylePlaceholdersWithPercentSignsAreFilledInWithoutLeavingPercentSigns() {
        assertEquals("https://cdn/thumb0-440x248.jpg", "https://cdn/thumb0-%{width}x%{height}.jpg".sized(440, 248))
    }

    @Test
    fun anEmptyUrlStaysEmpty() {
        // A VOD that is still being recorded has an empty thumbnail_url.
        assertEquals("", "".sized(440, 248))
    }

    @Test
    fun durationsParseHoursMinutesAndSecondsAndUnknownInputIsZero() {
        assertEquals(3723L, parseTwitchDuration("1h2m3s"))
        assertEquals(125L, parseTwitchDuration("2m5s"))
        assertEquals(45L, parseTwitchDuration("45s"))
        assertEquals(0L, parseTwitchDuration("soon"))
        assertEquals(0L, parseTwitchDuration(""))
    }
}
