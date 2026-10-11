package com.s0undplus.tv.ui.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.s0undplus.tv.HermeticRule
import com.s0undplus.tv.awaitCondition
import com.s0undplus.tv.fake.FakeTwitch
import com.s0undplus.twitch.playback.PlaybackOptions
import com.s0undplus.twitch.playback.PlaybackStream
import com.s0undplus.twitch.playback.Variant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real [AdBlocker] on a real device clock and real HTTP, reading playlists the fake serves. The playlists are
 * synthetic (built from the markers Xtra documents), so this proves the app reacts correctly to them, not that Twitch's
 * live playlists still look like this.
 */
@RunWith(AndroidJUnit4::class)
class AdBlockerDeviceTest {
    @get:Rule val hermetic = HermeticRule()

    // The blocker is written for one thread (the main dispatcher), so it gets exactly one here too.
    private val executor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())

    private val original = AtomicReference("live")
    private val altOne = AtomicReference("live")
    private val resolveCalls = AtomicInteger()
    private val failed = CopyOnWriteArrayList<String?>()
    private var job: Job? = null

    @Before
    fun serve() {
        fun route(name: String, title: AtomicReference<String>) =
            FakeTwitch.on("/hls/$name.m3u8") { FakeTwitch.text(playlist(title.get()), contentType = "application/vnd.apple.mpegurl") }
        route("original", original)
        route("one", altOne)
        route("two", AtomicReference("live"))
    }

    @After
    fun stop() {
        job?.cancel()
        scope.coroutineContext[Job]?.cancel()
        executor.shutdownNow()
    }

    private fun playlist(newestTitle: String) = """
        #EXTM3U
        #EXT-X-VERSION:3
        #EXT-X-TARGETDURATION:2
        #EXT-X-MEDIA-SEQUENCE:100
        #EXTINF:2.000,live
        seg100.ts
        #EXTINF:2.000,$newestTitle
        seg101.ts
    """.trimIndent()

    private fun source(label: String) = AdSource(label, PlaybackOptions(playerType = label))

    private fun altStream(name: String) = AltStream(
        source(name),
        PlaybackStream(
            "${FakeTwitch.baseUrl}/hls/$name-master.m3u8",
            listOf(Variant("720p", "${FakeTwitch.baseUrl}/hls/$name.m3u8", 1_000_000, 1280, 720, 30.0, "avc1.4D401F,mp4a.40.2")),
        ),
    )

    private fun blocker(lagMs: Long = 0, alts: List<AltStream> = emptyList()) = AdBlocker(
        http = FakeTwitch.httpClient(),
        liveOffsetMs = { lagMs },
        resolveAlt = { alts.getOrNull(resolveCalls.getAndIncrement()) },
        onAltFailed = { failed += it?.label },
    )

    private fun start(blocker: AdBlocker, swap: Boolean) {
        job = scope.launch { blocker.run({ "${FakeTwitch.baseUrl}/hls/original.m3u8" }, swap) }
    }

    private fun awaitState(blocker: AdBlocker, state: AdState, timeoutMs: Long = 15_000) =
        awaitCondition("the blocker to be $state (it is ${blocker.state})", timeoutMs) { blocker.state == state }

    @Test
    fun anAdAtTheLiveEdgeIsHiddenAndTheStreamIsGivenBackWhenItEnds() {
        val blocker = blocker()
        start(blocker, swap = false)
        Thread.sleep(2_500) // a few clean polls first
        assertEquals(AdState.Idle, blocker.state)

        original.set("Amazon|123")
        awaitState(blocker, AdState.Hiding)
        assertNull("hide-only mode never swaps", blocker.alt)

        original.set("live")
        awaitState(blocker, AdState.Idle)
        assertEquals("nothing was asked for in hide-only mode", 0, resolveCalls.get())
    }

    @Test
    fun withSwapTheAdFreeCopyIsPlayedForTheBreakAndThenTheOriginalReturns() {
        val blocker = blocker(alts = listOf(altStream("one")))
        start(blocker, swap = true)
        Thread.sleep(2_500)

        original.set("Amazon|123")
        awaitState(blocker, AdState.Swapped)
        assertEquals("one", blocker.alt?.source?.label)
        assertTrue("the copy is only looked up once", resolveCalls.get() == 1)

        original.set("live")
        awaitState(blocker, AdState.Idle)
        assertNull(blocker.alt)
    }

    @Test
    fun ifTheAdFreeCopyAlsoCarriesAnAdTheNextSourceTakesOver() {
        val blocker = blocker(alts = listOf(altStream("one"), altStream("two")))
        start(blocker, swap = true)
        Thread.sleep(2_500)

        original.set("Amazon|123")
        awaitState(blocker, AdState.Swapped)
        assertEquals("one", blocker.alt?.source?.label)

        altOne.set("Amazon|456") // the "ad-free" copy now has an ad at its edge too
        awaitCondition("the blocker to move on to source two (alt is ${blocker.alt?.source?.label})", 15_000) {
            blocker.state == AdState.Swapped && blocker.alt?.source?.label == "two"
        }
        assertEquals(listOf<String?>("one"), failed.toList())
    }

    @Test
    fun whenNoAdFreeCopyCanBeFoundTheAdStaysHiddenUntilItEnds() {
        val blocker = blocker(alts = emptyList())
        start(blocker, swap = true)
        Thread.sleep(2_500)

        original.set("Amazon|123")
        awaitState(blocker, AdState.Hiding)
        Thread.sleep(3_000) // long enough for a swap attempt to give up
        assertEquals(AdState.Hiding, blocker.state)
        assertNull(blocker.alt)

        original.set("live")
        awaitState(blocker, AdState.Idle)
    }

    @Test
    fun leavingTheScreenInTheMiddleOfABreakNeverLeavesThePictureHidden() {
        val blocker = blocker()
        start(blocker, swap = false)
        Thread.sleep(2_500)
        original.set("Amazon|123")
        awaitState(blocker, AdState.Hiding)

        runBlocking { job!!.cancelAndJoin() }
        assertEquals(AdState.Idle, blocker.state)
        assertNull(blocker.alt)
    }

    @Test
    fun aViewerBehindLiveIsNotHiddenBeforeTheAdReachesThem() {
        val lagMs = 4_000L
        val blocker = blocker(lagMs = lagMs)
        start(blocker, swap = false)
        Thread.sleep(2_500)

        val flippedAt = System.nanoTime()
        original.set("Amazon|123")
        awaitState(blocker, AdState.Hiding, timeoutMs = 20_000)
        val elapsedMs = (System.nanoTime() - flippedAt) / 1_000_000
        // The ad was at the edge no earlier than the flip, and the viewer is `lag` behind it; entering is due half a
        // second before that. A little slack for the request being in flight when the playlist flipped.
        assertTrue("hidden after only ${elapsedMs}ms for a viewer ${lagMs}ms behind", elapsedMs >= lagMs - 500 - 400)
    }
}
