package com.s0undplus.twitch.playback

import java.time.OffsetDateTime

/**
 * What the player knows about the newest end of the live media playlist, stripped of any player-library types so the
 * detection rules can be tested without a player.
 *
 * @property tags every `#EXT…` line of the playlist, in order (Media3 exposes these as `HlsMediaPlaylist.tags`)
 * @property newestSegmentTitle the `#EXTINF` title of the newest segment, i.e. the text after the comma
 * @property newestSegmentStartMs / [newestSegmentEndMs] wall-clock span of the newest segment from
 * `#EXT-X-PROGRAM-DATE-TIME`, or null when the playlist carries none
 */
data class MediaPlaylistView(
    val tags: List<String>,
    val newestSegmentTitle: String?,
    val newestSegmentStartMs: Long?,
    val newestSegmentEndMs: Long?,
)

/**
 * Recognises Twitch's *stitched* ads: Twitch splices ad segments into the same media playlist as the stream, marked
 * in one of two ways, and these rules (the ones Xtra uses) treat either as "an ad is at the live edge":
 *  - the newest segment's `#EXTINF` title names an ad provider (`Amazon`, `Adform`, `DCM`; Twitch appends `|…` details)
 *  - a `#EXT-X-DATERANGE` tag identifies itself as a stitched ad (ID `stitched-ad…`, CLASS `twitch-stitched-ad`, or any
 *    `X-TV-TWITCH-AD-*` attribute) and its time span covers the newest segment
 *
 * These markers are Twitch's internal convention and can change without notice; when they do, this simply stops
 * detecting and playback carries on exactly as it would without ad handling.
 */
object AdBreakDetector {
    private val AD_TITLES = setOf("amazon", "adform", "dcm")

    fun isAdAtEdge(view: MediaPlaylistView): Boolean = titleSaysAd(view.newestSegmentTitle) || daterangeSaysAd(view)

    private fun titleSaysAd(title: String?): Boolean {
        val provider = title?.substringBefore('|')?.trim()?.lowercase() ?: return false
        return provider in AD_TITLES
    }

    private fun daterangeSaysAd(view: MediaPlaylistView): Boolean {
        for (tag in view.tags) {
            if (!tag.startsWith("#EXT-X-DATERANGE:")) continue
            val attrs = MasterPlaylist.parseAttributes(tag.substringAfter(':'))
            val marked = attrs["ID"]?.startsWith("stitched-ad", ignoreCase = true) == true ||
                attrs["CLASS"].equals("twitch-stitched-ad", ignoreCase = true) ||
                attrs.keys.any { it.startsWith("X-TV-TWITCH-AD", ignoreCase = true) }
            if (marked && coversNewestSegment(attrs, view)) return true
        }
        return false
    }

    /**
     * Playlists keep old DATERANGE tags for as long as their segments are retained, so a tag only counts while its
     * span overlaps the newest segment. With no wall-clock times to compare, only an open-ended range (no end or
     * duration at all) is trusted as current.
     */
    private fun coversNewestSegment(attrs: Map<String, String>, view: MediaPlaylistView): Boolean {
        val start = attrs["START-DATE"]?.let(::parseDate)
        val end = attrs["END-DATE"]?.let(::parseDate)
            ?: (attrs["DURATION"] ?: attrs["PLANNED-DURATION"])?.toDoubleOrNull()
                ?.let { seconds -> start?.plus((seconds * 1000).toLong()) }
        val openEnded = end == null && attrs["END-DATE"] == null && attrs["DURATION"] == null &&
            attrs["PLANNED-DURATION"] == null
        val segStart = view.newestSegmentStartMs
        val segEnd = view.newestSegmentEndMs ?: segStart
        if (segStart == null || segEnd == null) return openEnded
        if (start == null) return false
        if (end == null) return openEnded && segEnd > start
        return segEnd > start && segStart < end
    }

    internal fun parseDate(value: String): Long? =
        try {
            OffsetDateTime.parse(value).toInstant().toEpochMilli()
        } catch (_: java.time.format.DateTimeParseException) {
            null
        }
}

/**
 * Reads the text of a live *media* playlist (the per-quality one, not the master) into the [MediaPlaylistView] the
 * detector needs. Polling the playlist ourselves — rather than reaching into the player's parsed copy — means the same
 * check also works while the player is busy with a different stream.
 */
object MediaPlaylistParser {
    /** Null when the text has no segments at all (an empty or error body). */
    fun view(text: String): MediaPlaylistView? {
        val tags = ArrayList<String>()
        var pendingStartMs: Long? = null
        var cursorMs: Long? = null
        var durationMs = 0L
        var title: String? = null
        var sawSegment = false
        var haveInfo = false
        var newestTitle: String? = null
        var newestStart: Long? = null
        var newestEnd: Long? = null

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXT-X-PROGRAM-DATE-TIME:") -> {
                    tags += line
                    pendingStartMs = AdBreakDetector.parseDate(line.substringAfter(':').trim())
                }
                line.startsWith("#EXTINF:") -> {
                    tags += line
                    val body = line.substringAfter(':')
                    durationMs = ((body.substringBefore(',').trim().toDoubleOrNull() ?: 0.0) * 1000).toLong()
                    title = if (',' in body) body.substringAfter(',').trim() else null
                    haveInfo = true
                }
                line.startsWith("#EXT") -> tags += line
                line.startsWith("#") -> Unit
                // A segment URI only counts right after its #EXTINF; anything else (e.g. a JSON error body) is noise.
                !haveInfo -> Unit
                else -> {
                    haveInfo = false
                    sawSegment = true
                    val start = pendingStartMs ?: cursorMs
                    val end = start?.plus(durationMs)
                    newestTitle = title
                    newestStart = start
                    newestEnd = end
                    cursorMs = end
                    pendingStartMs = null
                    title = null
                    durationMs = 0
                }
            }
        }
        return if (sawSegment) MediaPlaylistView(tags, newestTitle, newestStart, newestEnd) else null
    }
}

/**
 * Turns "is there an ad at the live edge right now?" observations into *when the viewer's own position* enters and
 * leaves the ad. The player plays [liveOffsetMs] behind the newest segment, so an ad seen at the edge reaches the
 * viewer that much later; muting or swapping at the moment of detection would silence the end of the previous
 * content, and unmuting at the moment the edge goes clean would let the tail of the ad through.
 *
 * Pure and clock-free: callers pass `nowMs` from any monotonic clock. Drive it with [onObservation] whenever the
 * playlist is seen (and at least every couple of seconds) and with [poll] on a short timer (a few hundred ms).
 */
class AdBreakPlanner(
    private val maxBreakMs: Long = MAX_BREAK_MS,
    private val enterLeadMs: Long = ENTER_LEAD_MS,
) {
    enum class Action { None, Enter, Exit }

    var isActive: Boolean = false
        private set

    private var activeSinceMs = 0L
    private var pendingEnterAtMs: Long? = null
    private var pendingExitAtMs: Long? = null

    /** After the safety cap forces an exit, ignore ads until the edge has been seen clean (or a marker stuck on forever would re-trigger every cap). */
    private var lockedOut = false

    /**
     * @param adAtEdge result of [AdBreakDetector.isAdAtEdge] for the newest playlist
     * @param liveOffsetMs how far behind the live edge the viewer is, or null if the player can't say
     */
    fun onObservation(nowMs: Long, adAtEdge: Boolean, liveOffsetMs: Long?) {
        if (lockedOut) {
            if (adAtEdge) return
            lockedOut = false
        }
        if (adAtEdge) {
            // The ad is (still) at the edge, so a pending exit was a flap between playlist refreshes.
            pendingExitAtMs = null
            if (!isActive && pendingEnterAtMs == null) {
                // Unknown lag: hide right away (a brief over-hide is harmless); the exit side waits a fixed time instead.
                val lag = liveOffsetMs ?: 0L
                pendingEnterAtMs = nowMs + maxOf(0L, lag - enterLeadMs)
            }
        } else if ((isActive || pendingEnterAtMs != null) && pendingExitAtMs == null) {
            // Short ads end at the edge before the viewer reaches them; the exit is still scheduled behind the entry.
            pendingExitAtMs = nowMs + (liveOffsetMs ?: UNKNOWN_LAG_EXIT_MS)
        }
    }

    /** The transition that is due at [nowMs], if any. At most one per call. */
    fun poll(nowMs: Long): Action {
        if (isActive && nowMs - activeSinceMs >= maxBreakMs) {
            clear()
            lockedOut = true
            return Action.Exit
        }
        val enterDue = pendingEnterAtMs?.let { nowMs >= it } == true
        val exitDue = pendingExitAtMs?.let { nowMs >= it } == true
        return when {
            exitDue && !isActive -> {
                // The whole ad passed between two polls (or before the entry fired): nothing left to hide.
                pendingEnterAtMs = null
                pendingExitAtMs = null
                Action.None
            }
            exitDue -> {
                clear()
                Action.Exit
            }
            enterDue && !isActive -> {
                pendingEnterAtMs = null
                isActive = true
                activeSinceMs = nowMs
                Action.Enter
            }
            else -> Action.None
        }
    }

    /** Forget everything, e.g. because the player was re-pointed at a new item and the old timeline no longer applies. */
    fun reset() {
        clear()
        lockedOut = false
    }

    private fun clear() {
        isActive = false
        pendingEnterAtMs = null
        pendingExitAtMs = null
    }

    companion object {
        /** Real breaks are 30–180 s; if "in an ad" lasts longer than this the detector is stuck, so give the stream back. */
        const val MAX_BREAK_MS = 3 * 60_000L

        /** Start hiding slightly before the computed arrival so a late playlist refresh doesn't leak the first frames. */
        const val ENTER_LEAD_MS = 500L

        /** When the player can't report its lag, wait this long before treating a clean edge as the end of the ad. */
        const val UNKNOWN_LAG_EXIT_MS = 6_000L
    }
}
