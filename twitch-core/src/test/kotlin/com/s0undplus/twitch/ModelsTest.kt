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
    fun searchBoxArtOfAFixedSizeBecomesATemplate() {
        val fixed = "https://static-cdn.jtvnw.net/ttv-boxart/32982_IGDB-52x72.jpg"
        assertEquals("https://static-cdn.jtvnw.net/ttv-boxart/32982_IGDB-{width}x{height}.jpg", sizeTemplate(fixed))
        assertEquals("https://static-cdn.jtvnw.net/ttv-boxart/32982_IGDB-144x192.jpg", sizeTemplate(fixed).sized(144, 192))
    }

    @Test
    fun aBoxArtTemplateAndAnEmptyUrlAreLeftAlone() {
        val template = "https://static-cdn.jtvnw.net/ttv-boxart/509658-{width}x{height}.jpg"
        assertEquals(template, sizeTemplate(template))
        assertEquals("", sizeTemplate(""))
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
