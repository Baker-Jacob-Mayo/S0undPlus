package com.s0undplus.tv.ui.util

import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Locale

object Format {
    /** 1234 → "1.2K", 15300 → "15K", 2_400_000 → "2.4M". */
    fun count(n: Int): String = when {
        n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
        n >= 10_000 -> "${n / 1_000}K"
        n >= 1_000 -> String.format(Locale.US, "%.1fK", n / 1_000.0)
        else -> n.toString()
    }

    /** 3723 → "1:02:03", 125 → "2:05". */
    fun clock(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(Locale.US, "%d:%02d", m, sec)
    }

    /** How long a stream has been live: "2h 15m", "40m". Null if [startedAt] is unparseable. */
    fun uptime(startedAt: String, now: Instant = Instant.now()): String? {
        val start = parse(startedAt) ?: return null
        val minutes = Duration.between(start, now).toMinutes().coerceAtLeast(0)
        return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
    }

    /** "today", "3 days ago", "2 months ago". */
    fun ago(iso: String, now: Instant = Instant.now()): String {
        val then = parse(iso) ?: return ""
        val days = Duration.between(then, now).toDays()
        return when {
            days < 1 -> "today"
            days == 1L -> "yesterday"
            days < 30 -> "$days days ago"
            days < 365 -> "${days / 30} months ago"
            else -> "${days / 365} years ago"
        }
    }

    private fun parse(iso: String): Instant? = try {
        Instant.parse(iso)
    } catch (_: DateTimeParseException) {
        null
    }
}
