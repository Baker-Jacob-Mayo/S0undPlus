package com.s0undplus.twitch.playback

import com.s0undplus.twitch.testHttp
import com.s0undplus.twitch.text
import java.time.Instant
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The playlists here are **synthetic**: built from the marker rules Xtra documents in its source, not captured from a
 * real Twitch ad break. They prove the rules behave as written; whether Twitch's live playlists still look like this is
 * something only a real stream can show.
 */
class AdBreakDetectorTest {
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun view(
        tags: List<String> = emptyList(),
        title: String? = "live",
        start: String? = "2026-01-01T12:00:10Z",
        end: String? = "2026-01-01T12:00:12Z",
    ) = MediaPlaylistView(tags, title, start?.let(::ms), end?.let(::ms))

    private fun range(attrs: String) = "#EXT-X-DATERANGE:$attrs"

    @Test
    fun ordinaryLiveSegmentIsNotAnAd() {
        assertFalse(AdBreakDetector.isAdAtEdge(view(tags = listOf("#EXTM3U", "#EXT-X-TARGETDURATION:6"))))
    }

    @Test
    fun adProviderTitlesAreAdsWhateverFollowsThePipe() {
        for (title in listOf("Amazon", "Amazon|1234|abc", "Adform|x", "DCM|y", "amazon|z")) {
            assertTrue(AdBreakDetector.isAdAtEdge(view(title = title)), title)
        }
    }

    @Test
    fun titlesThatMerelyStartLikeAProviderAreNotAds() {
        for (title in listOf("live", "Amazonian", "", null, "live|Amazon")) {
            assertFalse(AdBreakDetector.isAdAtEdge(view(title = title)), "$title")
        }
    }

    @Test
    fun stitchedAdDaterangeCoveringTheNewestSegmentIsAnAd() {
        val tag = range("""ID="stitched-ad-1234",CLASS="twitch-stitched-ad",START-DATE="2026-01-01T12:00:00Z",DURATION=30.000""")
        assertTrue(AdBreakDetector.isAdAtEdge(view(tags = listOf(tag))))
    }

    @Test
    fun anyOfTheThreeMarkersIdentifiesAnAd() {
        val common = """START-DATE="2026-01-01T12:00:00Z",DURATION=30"""
        val byId = range("""ID="stitched-ad-9",$common""")
        val byClass = range("""ID="x",CLASS="twitch-stitched-ad",$common""")
        val byAttribute = range("""ID="x",X-TV-TWITCH-AD-ROLL-TYPE="PREROLL",$common""")
        for (tag in listOf(byId, byClass, byAttribute)) {
            assertTrue(AdBreakDetector.isAdAtEdge(view(tags = listOf(tag))), tag)
        }
    }

    @Test
    fun rangeEndsAreTakenFromEndDateDurationOrPlannedDuration() {
        val segmentAfter = view(start = "2026-01-01T12:00:40Z", end = "2026-01-01T12:00:42Z", tags = emptyList())
        val tags = mapOf(
            "END-DATE" to """END-DATE="2026-01-01T12:00:30Z"""",
            "DURATION" to "DURATION=30.0",
            "PLANNED-DURATION" to "PLANNED-DURATION=30.0",
        )
        for ((name, end) in tags) {
            val tag = range("""ID="stitched-ad-1",START-DATE="2026-01-01T12:00:00Z",$end""")
            assertTrue(AdBreakDetector.isAdAtEdge(view(tags = listOf(tag))), "$name covers 12:00:10")
            assertFalse(AdBreakDetector.isAdAtEdge(segmentAfter.copy(tags = listOf(tag))), "$name is over by 12:00:40")
        }
    }

    @Test
    fun staleRangeDoesNotCountOnceTheEdgeHasMovedPastIt() {
        val tag = range("""ID="stitched-ad-1",START-DATE="2026-01-01T11:50:00Z",DURATION=30""")
        assertFalse(AdBreakDetector.isAdAtEdge(view(tags = listOf(tag))))
    }

    @Test
    fun rangeThatHasNotStartedYetDoesNotCount() {
        val tag = range("""ID="stitched-ad-1",START-DATE="2026-01-01T12:05:00Z",DURATION=30""")
        assertFalse(AdBreakDetector.isAdAtEdge(view(tags = listOf(tag))))
    }

    @Test
    fun segmentTouchingTheRangeEndExactlyIsNotInsideIt() {
        val tag = range("""ID="stitched-ad-1",START-DATE="2026-01-01T12:00:00Z",END-DATE="2026-01-01T12:00:10Z"""")
        assertFalse(AdBreakDetector.isAdAtEdge(view(tags = listOf(tag), start = "2026-01-01T12:00:10Z", end = "2026-01-01T12:00:12Z")))
    }

    @Test
    fun ordinaryDaterangesAreIgnored() {
        val tag = range("""ID="splice-1",CLASS="timestamp",START-DATE="2026-01-01T12:00:00Z",DURATION=30""")
        assertFalse(AdBreakDetector.isAdAtEdge(view(tags = listOf(tag))))
    }

    @Test
    fun withoutProgramDateTimeOnlyAnOpenEndedRangeIsTrusted() {
        val bounded = range("""ID="stitched-ad-1",START-DATE="2026-01-01T12:00:00Z",DURATION=30""")
        val open = range("""ID="stitched-ad-1",START-DATE="2026-01-01T12:00:00Z"""")
        assertFalse(AdBreakDetector.isAdAtEdge(view(tags = listOf(bounded), start = null, end = null)))
        assertTrue(AdBreakDetector.isAdAtEdge(view(tags = listOf(open), start = null, end = null)))
    }

    @Test
    fun openEndedRangeCountsOnceTheNewestSegmentIsPastItsStart() {
        val open = range("""ID="stitched-ad-1",START-DATE="2026-01-01T12:00:00Z"""")
        assertTrue(AdBreakDetector.isAdAtEdge(view(tags = listOf(open))))
        val future = range("""ID="stitched-ad-1",START-DATE="2026-01-01T13:00:00Z"""")
        assertFalse(AdBreakDetector.isAdAtEdge(view(tags = listOf(future))))
    }

    @Test
    fun plannedDurationComesFromTheStitchedAdDaterangeCoveringTheEdge() {
        val tag = range("""ID="stitched-ad-1",START-DATE="2026-01-01T12:00:00Z",DURATION=30.000""")
        assertEquals(30_000L, AdBreakDetector.plannedDurationMs(view(tags = listOf(tag))))
        val planned = range("""ID="stitched-ad-1",START-DATE="2026-01-01T12:00:00Z",PLANNED-DURATION=15.5""")
        assertEquals(15_500L, AdBreakDetector.plannedDurationMs(view(tags = listOf(planned))))
    }

    @Test
    fun noPlannedDurationWhenThereIsNoneOrItIsNotUsable() {
        val unrelated = range("""ID="splice-1",START-DATE="2026-01-01T12:00:00Z",DURATION=30""")
        val stale = range("""ID="stitched-ad-1",START-DATE="2026-01-01T11:00:00Z",DURATION=30""")
        val openEnded = range("""ID="stitched-ad-1",START-DATE="2026-01-01T12:00:00Z"""")
        val absurd = range("""ID="stitched-ad-1",START-DATE="2026-01-01T12:00:00Z",DURATION=100000""")
        val zero = range("""ID="stitched-ad-1",START-DATE="2026-01-01T12:00:00Z",DURATION=0""")
        for (tag in listOf(unrelated, stale, openEnded, absurd, zero)) {
            assertEquals(null, AdBreakDetector.plannedDurationMs(view(tags = listOf(tag))), tag)
        }
        assertEquals(null, AdBreakDetector.plannedDurationMs(view(title = "Amazon|1")))
    }

    @Test
    fun datesSoFarOutThatTheyOverflowNeverCrashDetection() {
        val farFuture = range("""ID="stitched-ad-1",START-DATE="+999999999-12-31T23:59:59Z",DURATION=30""")
        val farPast = range("""ID="stitched-ad-1",START-DATE="2026-01-01T12:00:00Z",END-DATE="-999999999-01-01T00:00:00Z"""")
        val hugeDuration = range("""ID="stitched-ad-1",START-DATE="2026-01-01T12:00:00Z",DURATION=1e30""")
        for (tag in listOf(farFuture, farPast, hugeDuration)) {
            assertFalse(AdBreakDetector.isAdAtEdge(view(tags = listOf(tag))), tag)
        }
        assertEquals(null, AdBreakDetector.parseDate("+999999999-12-31T23:59:59Z"))
    }

    @Test
    fun malformedDatesNeverCrashAndNeverMatch() {
        val tag = range("""ID="stitched-ad-1",START-DATE="not a date",DURATION=30""")
        assertFalse(AdBreakDetector.isAdAtEdge(view(tags = listOf(tag))))
        assertEquals(null, AdBreakDetector.parseDate("nonsense"))
    }
}

class AdBreakPlannerTest {
    private val planner = AdBreakPlanner(maxBreakMs = 180_000, enterLeadMs = 500)

    @Test
    fun entersWhenTheAdReachesTheViewerAndLeavesWhenTheCleanEdgeDoes() {
        // Viewer is 4 s behind live: an ad seen at the edge at t=0 is heard at t=4000 (we hide at 3500).
        planner.onObservation(0, adAtEdge = true, liveOffsetMs = 4_000)
        assertEquals(AdBreakPlanner.Action.None, planner.poll(3_400))
        assertEquals(AdBreakPlanner.Action.Enter, planner.poll(3_500))
        assertTrue(planner.isActive)
        assertEquals(AdBreakPlanner.Action.None, planner.poll(20_000))

        // The edge goes clean at t=30_000; the viewer still has 4 s of ad left.
        planner.onObservation(30_000, adAtEdge = false, liveOffsetMs = 4_000)
        assertEquals(AdBreakPlanner.Action.None, planner.poll(33_900))
        assertEquals(AdBreakPlanner.Action.Exit, planner.poll(34_000))
        assertFalse(planner.isActive)
    }

    @Test
    fun repeatedObservationsDoNotPushTheEntryBack() {
        planner.onObservation(0, true, 4_000)
        planner.onObservation(1_000, true, 4_000)
        planner.onObservation(2_000, true, 4_000)
        assertEquals(AdBreakPlanner.Action.Enter, planner.poll(3_500))
    }

    @Test
    fun adShorterThanTheViewersLagIsStillHiddenWhenItArrives() {
        planner.onObservation(0, true, 10_000)
        planner.onObservation(2_000, false, 10_000) // ad was only ~2 s long at the edge
        assertEquals(AdBreakPlanner.Action.None, planner.poll(5_000))
        assertEquals(AdBreakPlanner.Action.Enter, planner.poll(9_500))
        assertEquals(AdBreakPlanner.Action.None, planner.poll(11_900))
        assertEquals(AdBreakPlanner.Action.Exit, planner.poll(12_000))
    }

    @Test
    fun cleanFlapBetweenPlaylistRefreshesDoesNotEndTheBreak() {
        planner.onObservation(0, true, 2_000)
        assertEquals(AdBreakPlanner.Action.Enter, planner.poll(1_500))
        planner.onObservation(10_000, false, 2_000)
        planner.onObservation(11_000, true, 2_000) // the ad was still going
        assertEquals(AdBreakPlanner.Action.None, planner.poll(13_000))
        assertTrue(planner.isActive)
        planner.onObservation(20_000, false, 2_000)
        assertEquals(AdBreakPlanner.Action.Exit, planner.poll(22_000))
    }

    @Test
    fun secondBreakAfterTheFirstEnteredAgain() {
        planner.onObservation(0, true, 1_000)
        assertEquals(AdBreakPlanner.Action.Enter, planner.poll(500))
        planner.onObservation(5_000, false, 1_000)
        assertEquals(AdBreakPlanner.Action.Exit, planner.poll(6_000))
        planner.onObservation(100_000, true, 1_000)
        assertEquals(AdBreakPlanner.Action.Enter, planner.poll(100_500))
    }

    @Test
    fun entersImmediatelyWhenTheViewerIsAlmostLive() {
        planner.onObservation(1_000, true, 200)
        assertEquals(AdBreakPlanner.Action.Enter, planner.poll(1_000))
    }

    @Test
    fun unknownLagHidesAtOnceAndWaitsAFixedTimeBeforeExiting() {
        planner.onObservation(0, true, null)
        assertEquals(AdBreakPlanner.Action.Enter, planner.poll(0))
        planner.onObservation(10_000, false, null)
        assertEquals(AdBreakPlanner.Action.None, planner.poll(10_000 + AdBreakPlanner.UNKNOWN_LAG_EXIT_MS - 1))
        assertEquals(AdBreakPlanner.Action.Exit, planner.poll(10_000 + AdBreakPlanner.UNKNOWN_LAG_EXIT_MS))
    }

    @Test
    fun hardCapForcesExitAndIgnoresAStuckMarkerUntilTheEdgeIsClean() {
        planner.onObservation(0, true, 0)
        assertEquals(AdBreakPlanner.Action.Enter, planner.poll(0))
        for (t in 10_000..170_000 step 10_000) planner.onObservation(t.toLong(), true, 0) // the marker keeps showing
        planner.onObservation(175_000, true, 0)
        assertEquals(AdBreakPlanner.Action.None, planner.poll(179_999))
        assertEquals(AdBreakPlanner.Action.Exit, planner.poll(180_000))
        assertFalse(planner.isActive)
        assertTrue(planner.isLockedOut)

        // The marker is still there: it must not re-trigger a hide.
        planner.onObservation(181_000, true, 0)
        assertEquals(AdBreakPlanner.Action.None, planner.poll(182_000))
        assertFalse(planner.isActive)

        // Once the edge has been seen clean, the next real ad works again.
        planner.onObservation(190_000, false, 0)
        assertFalse(planner.isLockedOut)
        planner.onObservation(200_000, true, 0)
        assertEquals(AdBreakPlanner.Action.Enter, planner.poll(200_000))
    }

    @Test
    fun wholeAdPassingBetweenPollsLeavesNothingToHide() {
        planner.onObservation(0, true, 1_000)
        planner.onObservation(100, false, 1_000)
        assertEquals(AdBreakPlanner.Action.None, planner.poll(50_000))
        assertFalse(planner.isActive)
        assertEquals(AdBreakPlanner.Action.None, planner.poll(60_000))
    }

    @Test
    fun resetDropsPendingAndActiveStateWithoutLockingOut() {
        planner.onObservation(0, true, 1_000)
        assertEquals(AdBreakPlanner.Action.Enter, planner.poll(500))
        planner.reset()
        assertFalse(planner.isActive)
        assertEquals(AdBreakPlanner.Action.None, planner.poll(10_000))
        planner.onObservation(11_000, true, 1_000)
        assertEquals(AdBreakPlanner.Action.Enter, planner.poll(11_500))
    }

    @Test
    fun theScheduleFollowsTheCurrentLagWhenTheViewerSeeksToLiveWhileTheAdIsPending() {
        planner.onObservation(0, true, 10_000) // would arrive at 9_500
        planner.onObservation(2_000, true, 3_000) // the viewer jumped to live: arrival is 0 + 3_000 - 500
        assertEquals(AdBreakPlanner.Action.None, planner.poll(2_400))
        assertEquals(AdBreakPlanner.Action.Enter, planner.poll(2_500))
    }

    @Test
    fun theScheduleMovesOutWhenThePlayerFallsFurtherBehindWhilePaused() {
        planner.onObservation(0, true, 2_000) // would arrive at 1_500
        planner.onObservation(1_000, true, 12_000) // paused: the lag has grown
        assertEquals(AdBreakPlanner.Action.None, planner.poll(1_500))
        assertEquals(AdBreakPlanner.Action.None, planner.poll(11_400))
        assertEquals(AdBreakPlanner.Action.Enter, planner.poll(11_500))
    }

    @Test
    fun theExitFollowsTheCurrentLagToo() {
        planner.onObservation(0, true, 1_000)
        assertEquals(AdBreakPlanner.Action.Enter, planner.poll(500))
        planner.onObservation(10_000, false, 8_000) // first clean look, lag 8 s: exit at 18_000
        planner.onObservation(12_000, false, 2_000) // the viewer caught up to live: exit at 10_000 + 2_000
        assertEquals(AdBreakPlanner.Action.Exit, planner.poll(12_000))
    }

    @Test
    fun implausibleLagValuesAreTreatedAsUnknown() {
        planner.onObservation(0, true, -3_000)
        assertEquals(AdBreakPlanner.Action.Enter, planner.poll(0)) // unknown lag: hide at once
        planner.onObservation(10_000, false, 300_000)
        assertEquals(AdBreakPlanner.Action.None, planner.poll(10_000 + AdBreakPlanner.UNKNOWN_LAG_EXIT_MS - 1))
        assertEquals(AdBreakPlanner.Action.Exit, planner.poll(10_000 + AdBreakPlanner.UNKNOWN_LAG_EXIT_MS))
    }

    @Test
    fun aBreakEndsWhenNoObservationArrivesForTooLongAndRestartsWhenTheyDo() {
        planner.onObservation(0, true, 0)
        assertEquals(AdBreakPlanner.Action.Enter, planner.poll(0))
        assertEquals(AdBreakPlanner.Action.None, planner.poll(AdBreakPlanner.STALE_OBSERVATION_MS - 1))
        assertEquals(AdBreakPlanner.Action.Exit, planner.poll(AdBreakPlanner.STALE_OBSERVATION_MS))
        assertFalse(planner.isActive)
        assertFalse(planner.isLockedOut)

        planner.onObservation(40_000, true, 0) // the playlist is readable again and the ad is still there
        assertEquals(AdBreakPlanner.Action.Enter, planner.poll(40_000))
    }

    @Test
    fun remainingTimeCountsDownFromWhenTheViewerReachedTheAdAndOnlyWhileItIsActive() {
        assertEquals(null, planner.remainingMs(0))
        planner.onObservation(0, true, 2_000, plannedDurationMs = 30_000)
        assertEquals(null, planner.remainingMs(500)) // the viewer hasn't reached the ad yet
        assertEquals(AdBreakPlanner.Action.Enter, planner.poll(1_500))
        assertEquals(30_000L, planner.remainingMs(1_500))
        planner.onObservation(10_000, true, 2_000, plannedDurationMs = 30_000)
        assertEquals(21_500L, planner.remainingMs(10_000))
        assertEquals(0L, planner.remainingMs(60_000)) // never negative
        planner.onObservation(10_000, false, 2_000)
        assertEquals(AdBreakPlanner.Action.Exit, planner.poll(12_000))
        assertEquals(null, planner.remainingMs(12_000))
    }

    @Test
    fun noRemainingTimeWhenThePlaylistNeverSaidHowLongTheBreakLasts() {
        planner.onObservation(0, true, 0)
        assertEquals(AdBreakPlanner.Action.Enter, planner.poll(0))
        assertEquals(null, planner.remainingMs(1_000))
    }

    @Test
    fun cleanObservationsAloneDoNothing() {
        planner.onObservation(0, false, 3_000)
        assertEquals(AdBreakPlanner.Action.None, planner.poll(10_000))
        assertFalse(planner.isActive)
    }
}

class MediaPlaylistParserTest {
    private fun playlist(vararg segments: Triple<String?, String, String>, head: String = "") = buildString {
        appendLine("#EXTM3U")
        appendLine("#EXT-X-VERSION:3")
        appendLine("#EXT-X-TARGETDURATION:6")
        if (head.isNotEmpty()) appendLine(head)
        for ((pdt, info, uri) in segments) {
            if (pdt != null) appendLine("#EXT-X-PROGRAM-DATE-TIME:$pdt")
            appendLine(info)
            appendLine(uri)
        }
    }

    @Test
    fun readsTheNewestSegmentsTitleAndWallClockSpan() {
        val text = playlist(
            Triple("2026-01-01T12:00:00.000Z", "#EXTINF:2.000,live", "https://e/1.ts"),
            Triple("2026-01-01T12:00:02.000Z", "#EXTINF:2.000,Amazon|123|x", "https://e/2.ts"),
        )
        val view = MediaPlaylistParser.view(text)!!
        assertEquals("Amazon|123|x", view.newestSegmentTitle)
        assertEquals(Instant.parse("2026-01-01T12:00:02Z").toEpochMilli(), view.newestSegmentStartMs)
        assertEquals(Instant.parse("2026-01-01T12:00:04Z").toEpochMilli(), view.newestSegmentEndMs)
        assertTrue(AdBreakDetector.isAdAtEdge(view))
    }

    @Test
    fun segmentsAfterTheFirstDateStampAreTimedByTheirDurations() {
        val text = playlist(
            Triple("2026-01-01T12:00:00Z", "#EXTINF:2.000,live", "https://e/1.ts"),
            Triple(null, "#EXTINF:2.000,live", "https://e/2.ts"),
            Triple(null, "#EXTINF:1.500,live", "https://e/3.ts"),
        )
        val view = MediaPlaylistParser.view(text)!!
        assertEquals(Instant.parse("2026-01-01T12:00:04Z").toEpochMilli(), view.newestSegmentStartMs)
        assertEquals(Instant.parse("2026-01-01T12:00:05.500Z").toEpochMilli(), view.newestSegmentEndMs)
    }

    @Test
    fun withoutAnyDateStampTimesAreUnknownButTheTitleStillCounts() {
        val view = MediaPlaylistParser.view(playlist(Triple(null, "#EXTINF:2.000,Adform|1", "https://e/1.ts")))!!
        assertEquals(null, view.newestSegmentStartMs)
        assertEquals(null, view.newestSegmentEndMs)
        assertTrue(AdBreakDetector.isAdAtEdge(view))
    }

    @Test
    fun cleanLivePlaylistIsNotAnAdAndKeepsItsDaterangesAsTags() {
        val head = """#EXT-X-DATERANGE:ID="splice-1",CLASS="timestamp",START-DATE="2026-01-01T11:00:00Z",DURATION=5"""
        val view = MediaPlaylistParser.view(
            playlist(Triple("2026-01-01T12:00:00Z", "#EXTINF:2.000,live", "https://e/1.ts"), head = head),
        )!!
        assertFalse(AdBreakDetector.isAdAtEdge(view))
        assertTrue(view.tags.any { it.startsWith("#EXT-X-DATERANGE:") })
    }

    @Test
    fun stitchedAdDaterangeInATextPlaylistIsDetectedEndToEnd() {
        val head = """#EXT-X-DATERANGE:ID="stitched-ad-1",CLASS="twitch-stitched-ad",START-DATE="2026-01-01T11:59:50Z",DURATION=30.0"""
        val view = MediaPlaylistParser.view(
            playlist(Triple("2026-01-01T12:00:00Z", "#EXTINF:2.000,live", "https://e/1.ts"), head = head),
        )!!
        assertTrue(AdBreakDetector.isAdAtEdge(view))
    }

    @Test
    fun emptyOrErrorBodiesGiveNoView() {
        assertEquals(null, MediaPlaylistParser.view(""))
        assertEquals(null, MediaPlaylistParser.view("""{"error":"Forbidden"}"""))
        assertEquals(null, MediaPlaylistParser.view("#EXTM3U\n#EXT-X-ENDLIST\n"))
    }

    @Test
    fun extinfWithoutATitleOrDurationDoesNotCrash() {
        val view = MediaPlaylistParser.view("#EXTM3U\n#EXTINF:abc\nhttps://e/1.ts\n")!!
        assertEquals(null, view.newestSegmentTitle)
    }
}

class MediaPlaylistFetcherTest {
    @Test
    fun fetchesAndParsesAndSwallowsEveryKindOfFailure() = runTest {
        val server = MockWebServer().also { it.start() }
        try {
            server.enqueue(text("#EXTM3U\n#EXTINF:2.000,Amazon|1\nhttps://e/1.ts\n"))
            server.enqueue(text("forbidden", 403))
            server.enqueue(text("not a playlist"))
            val url = server.url("/v.m3u8").toString()

            assertTrue(AdBreakDetector.isAdAtEdge(MediaPlaylistFetcher.view(testHttp(), url)!!))
            assertEquals(null, MediaPlaylistFetcher.view(testHttp(), url))
            assertEquals(null, MediaPlaylistFetcher.view(testHttp(), url))
            assertEquals(null, MediaPlaylistFetcher.view(testHttp(), "not a url"))
        } finally {
            server.close()
        }
        // The server is gone now: a connection failure is also just "no answer".
        assertEquals(null, MediaPlaylistFetcher.view(testHttp(), "http://127.0.0.1:1/v.m3u8"))
    }

    @Test
    fun aStalledPlaylistFetchGivesUpAtTheTimeoutInsteadOfHangingTheDetector() = runTest {
        val slow = MockWebServer().also { it.start() }
        try {
            slow.enqueue(
                mockwebserver3.MockResponse.Builder()
                    .body("#EXTM3U\n#EXTINF:2.000,live\nhttps://e/1.ts\n")
                    .headersDelay(3, java.util.concurrent.TimeUnit.SECONDS)
                    .build(),
            )
            val started = System.nanoTime()
            assertEquals(null, MediaPlaylistFetcher.view(testHttp(), slow.url("/v.m3u8").toString(), timeoutMs = 300))
            val tookMs = (System.nanoTime() - started) / 1_000_000
            assertTrue(tookMs < 2_000, "took ${tookMs}ms")
        } finally {
            slow.close()
        }
    }
}
