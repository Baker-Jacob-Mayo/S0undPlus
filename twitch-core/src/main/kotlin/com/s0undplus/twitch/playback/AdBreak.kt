package com.s0undplus.twitch.playback

import com.s0undplus.twitch.await
import java.time.DateTimeException
import java.time.OffsetDateTime
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

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

    /**
     * How long the ad break at the edge is planned to last, when the playlist says so: the `DURATION` (or
     * `PLANNED-DURATION`) of the stitched-ad `DATERANGE` that covers the newest segment. Null when there is none, or it
     * is not a usable number; callers show no countdown then.
     */
    fun plannedDurationMs(view: MediaPlaylistView): Long? {
        for (tag in view.tags) {
            if (!tag.startsWith("#EXT-X-DATERANGE:")) continue
            val attrs = MasterPlaylist.parseAttributes(tag.substringAfter(':'))
            if (!isStitchedAd(attrs) || !coversNewestSegment(attrs, view)) continue
            val seconds = (attrs["DURATION"] ?: attrs["PLANNED-DURATION"])?.toDoubleOrNull() ?: continue
            if (seconds.isNaN() || seconds <= 0 || seconds > MAX_PLAUSIBLE_BREAK_SECONDS) continue
            return (seconds * 1000).toLong()
        }
        return null
    }

    private const val MAX_PLAUSIBLE_BREAK_SECONDS = 600.0

    private fun titleSaysAd(title: String?): Boolean {
        val provider = title?.substringBefore('|')?.trim()?.lowercase() ?: return false
        return provider in AD_TITLES
    }

    private fun daterangeSaysAd(view: MediaPlaylistView): Boolean {
        for (tag in view.tags) {
            if (!tag.startsWith("#EXT-X-DATERANGE:")) continue
            val attrs = MasterPlaylist.parseAttributes(tag.substringAfter(':'))
            if (isStitchedAd(attrs) && coversNewestSegment(attrs, view)) return true
        }
        return false
    }

    private fun isStitchedAd(attrs: Map<String, String>): Boolean =
        attrs["ID"]?.startsWith("stitched-ad", ignoreCase = true) == true ||
            attrs["CLASS"].equals("twitch-stitched-ad", ignoreCase = true) ||
            attrs.keys.any { it.startsWith("X-TV-TWITCH-AD", ignoreCase = true) }

    /**
     * Playlists keep old DATERANGE tags for as long as their segments are retained, so a tag only counts while its
     * span overlaps the newest segment. With no wall-clock times to compare, only an open-ended range (no end or
     * duration at all) is trusted as current. (Unlike Xtra, which ignores a range whose end it cannot work out, such an
     * open-ended range counts here; the planner's safety cap is what ends a marker that never goes away.)
     */
    private fun coversNewestSegment(attrs: Map<String, String>, view: MediaPlaylistView): Boolean {
        val start = attrs["START-DATE"]?.let(::parseDate)
        val end = attrs["END-DATE"]?.let(::parseDate)
            ?: (attrs["DURATION"] ?: attrs["PLANNED-DURATION"])?.toDoubleOrNull()
                ?.let { seconds -> start?.let { plusMillis(it, seconds * 1000) } }
        val openEnded = end == null && attrs["END-DATE"] == null && attrs["DURATION"] == null &&
            attrs["PLANNED-DURATION"] == null
        val segStart = view.newestSegmentStartMs
        val segEnd = view.newestSegmentEndMs ?: segStart
        if (segStart == null || segEnd == null) return openEnded
        if (start == null) return false
        if (end == null) return openEnded && segEnd > start
        return segEnd > start && segStart < end
    }

    /** Null for anything unreadable, including years so far out that the epoch milliseconds don't fit in a Long. */
    internal fun parseDate(value: String): Long? =
        try {
            OffsetDateTime.parse(value).toInstant().toEpochMilli()
        } catch (_: DateTimeException) {
            null
        } catch (_: ArithmeticException) {
            null
        }

    private fun plusMillis(base: Long, millis: Double): Long? =
        if (millis.isNaN() || millis.isInfinite()) {
            null
        } else {
            try {
                Math.addExact(base, millis.toLong())
            } catch (_: ArithmeticException) {
                null
            }
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
 * Fetches a live media playlist and reads it; every failure is just "no answer this time". Each fetch has a hard
 * [timeoutMs] limit (a stalled request would otherwise leave an ad audible, or the screen hidden, for as long as the
 * stall lasts), reads at most [MAX_BODY_BYTES], and does its blocking body read off the caller's thread.
 */
object MediaPlaylistFetcher {
    const val TIMEOUT_MS = 4_000L
    const val MAX_BODY_BYTES = 512L * 1024

    suspend fun view(http: OkHttpClient, url: String, timeoutMs: Long = TIMEOUT_MS): MediaPlaylistView? {
        val request = try {
            Request.Builder().url(url).build()
        } catch (_: IllegalArgumentException) {
            return null
        }
        val client = ProxyClients.withCallTimeout(http, timeoutMs)
        return try {
            withContext(Dispatchers.IO) {
                client.newCall(request).await(MAX_BODY_BYTES).use { response ->
                    if (response.isSuccessful) MediaPlaylistParser.view(response.peekBody(MAX_BODY_BYTES).string()) else null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
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
    private val staleObservationMs: Long = STALE_OBSERVATION_MS,
) {
    enum class Action { None, Enter, Exit }

    var isActive: Boolean = false
        private set

    /** True after the safety cap forced an exit and until the edge has been seen clean again. */
    var isLockedOut: Boolean = false
        private set

    private var activeSinceMs = 0L
    private var lastObservationMs: Long? = null

    // When the ad (or the clean edge after it) was first seen. The viewer reaches that point `lag` later, and the lag
    // can change meanwhile (a pause or a seek to live), so the due times are recomputed from the *current* lag at every
    // observation instead of being fixed once.
    private var firstAdSeenMs: Long? = null
    private var firstCleanSeenMs: Long? = null
    private var pendingEnterAtMs: Long? = null
    private var pendingExitAtMs: Long? = null
    private var plannedBreakMs: Long? = null

    /**
     * Time left in the break the viewer is in, if the playlist said how long it lasts; null when no break is active
     * or no duration is known. Counted from the moment the viewer reached the ad, so it is an estimate.
     */
    fun remainingMs(nowMs: Long): Long? {
        val planned = plannedBreakMs ?: return null
        return if (isActive) maxOf(0L, planned - (nowMs - activeSinceMs)) else null
    }

    /**
     * @param adAtEdge result of [AdBreakDetector.isAdAtEdge] for the newest playlist
     * @param liveOffsetMs how far behind the live edge the viewer is, or null if the player can't say; values outside
     * 0..[MAX_PLAUSIBLE_LAG_MS] (a skewed device clock, say) are treated as unknown
     * @param plannedDurationMs how long the playlist says the break lasts ([AdBreakDetector.plannedDurationMs]), if it does
     */
    fun onObservation(nowMs: Long, adAtEdge: Boolean, liveOffsetMs: Long?, plannedDurationMs: Long? = null) {
        lastObservationMs = nowMs
        val lag = liveOffsetMs?.takeIf { it in 0..MAX_PLAUSIBLE_LAG_MS }
        if (isLockedOut) {
            if (adAtEdge) return
            isLockedOut = false
        }
        if (adAtEdge) {
            if (plannedDurationMs != null && plannedDurationMs > 0) plannedBreakMs = plannedDurationMs
            // The ad is (still) at the edge, so a pending exit was a flap between playlist refreshes.
            pendingExitAtMs = null
            firstCleanSeenMs = null
            if (!isActive) {
                val first = firstAdSeenMs ?: nowMs.also { firstAdSeenMs = it }
                // Unknown lag: hide right away (a brief over-hide is harmless); the exit side waits a fixed time instead.
                pendingEnterAtMs = first + maxOf(0L, (lag ?: 0L) - enterLeadMs)
            }
        } else if (isActive || firstAdSeenMs != null) {
            // Short ads end at the edge before the viewer reaches them; the exit is still scheduled behind the entry.
            val clean = firstCleanSeenMs ?: nowMs.also { firstCleanSeenMs = it }
            pendingExitAtMs = clean + (lag ?: UNKNOWN_LAG_EXIT_MS)
            firstAdSeenMs?.let { pendingEnterAtMs = it + maxOf(0L, (lag ?: 0L) - enterLeadMs) }
        }
    }

    /** The transition that is due at [nowMs], if any. At most one per call. */
    fun poll(nowMs: Long): Action {
        if (isActive && nowMs - activeSinceMs >= maxBreakMs) {
            clear()
            isLockedOut = true
            return Action.Exit
        }
        // Nothing has been learned for a while (failed or hung playlist fetches): don't stay hidden on stale information.
        // If the ad is still there, the next observation simply starts a new break.
        if (isActive && lastObservationMs?.let { nowMs - it >= staleObservationMs } == true) {
            clear()
            return Action.Exit
        }
        val enterDue = pendingEnterAtMs?.let { nowMs >= it } == true
        val exitDue = pendingExitAtMs?.let { nowMs >= it } == true
        return when {
            exitDue && !isActive -> {
                // The whole ad passed between two polls (or before the entry fired): nothing left to hide.
                clear()
                Action.None
            }
            exitDue -> {
                clear()
                Action.Exit
            }
            enterDue && !isActive -> {
                pendingEnterAtMs = null
                firstAdSeenMs = null
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
        isLockedOut = false
        lastObservationMs = null
    }

    private fun clear() {
        isActive = false
        plannedBreakMs = null
        firstAdSeenMs = null
        firstCleanSeenMs = null
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

        /** A live lag outside 0..this is not believable; it is ignored rather than trusted. */
        const val MAX_PLAUSIBLE_LAG_MS = 60_000L

        /** A break with no playlist observation for this long is ended instead of trusting what was last known. */
        const val STALE_OBSERVATION_MS = 30_000L
    }
}
