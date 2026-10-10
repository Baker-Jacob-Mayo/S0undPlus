package com.s0undplus.twitch.playback

import java.time.Instant
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
        planner.onObservation(100_000, true, 0)
        assertEquals(AdBreakPlanner.Action.None, planner.poll(179_999))
        assertEquals(AdBreakPlanner.Action.Exit, planner.poll(180_000))
        assertFalse(planner.isActive)

        // The marker is still there: it must not re-trigger a hide.
        planner.onObservation(181_000, true, 0)
        assertEquals(AdBreakPlanner.Action.None, planner.poll(182_000))
        assertFalse(planner.isActive)

        // Once the edge has been seen clean, the next real ad works again.
        planner.onObservation(190_000, false, 0)
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
    fun cleanObservationsAloneDoNothing() {
        planner.onObservation(0, false, 3_000)
        assertEquals(AdBreakPlanner.Action.None, planner.poll(10_000))
        assertFalse(planner.isActive)
    }
}
