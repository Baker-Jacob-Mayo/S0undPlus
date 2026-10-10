package com.s0undplus.tv.ui.util

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    private val now = Instant.parse("2026-06-01T12:00:00Z")

    @Test
    fun countAbbreviatesThousandsAndMillions() {
        assertEquals("999", Format.count(999))
        assertEquals("1.2K", Format.count(1_234))
        assertEquals("15K", Format.count(15_300))
        assertEquals("2.4M", Format.count(2_400_000))
    }

    @Test
    fun clockShowsMinutesOrHoursAndNeverGoesNegative() {
        assertEquals("2:05", Format.clock(125))
        assertEquals("1:02:03", Format.clock(3_723))
        assertEquals("0:00", Format.clock(-5))
    }

    @Test
    fun uptimeIsMinutesThenHoursAndMinutes() {
        assertEquals("40m", Format.uptime("2026-06-01T11:20:00Z", now))
        assertEquals("2h 15m", Format.uptime("2026-06-01T09:45:00Z", now))
        assertEquals("0m", Format.uptime("2026-06-01T13:00:00Z", now)) // a start in the future is clamped
        assertEquals(null, Format.uptime("garbage", now))
    }

    @Test
    fun agoUsesDaysMonthsAndYearsWithCorrectPlurals() {
        fun daysAgo(days: Long) = Format.ago(now.minusSeconds(days * 86_400).toString(), now)
        assertEquals("today", daysAgo(0))
        assertEquals("yesterday", daysAgo(1))
        assertEquals("3 days ago", daysAgo(3))
        assertEquals("1 month ago", daysAgo(30))
        assertEquals("1 month ago", daysAgo(59))
        assertEquals("2 months ago", daysAgo(60))
        assertEquals("1 year ago", daysAgo(365))
        assertEquals("2 years ago", daysAgo(800))
        assertEquals("", Format.ago("not a date", now))
    }

    @Test
    fun agoMillisMatchesAgo() {
        assertEquals("3 days ago", Format.agoMillis(now.minusSeconds(3 * 86_400).toEpochMilli(), now))
    }
}
