package com.s0undplus.tv.ui.player

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.s0undplus.tv.FailureCapture
import com.s0undplus.tv.HermeticRule
import com.s0undplus.tv.MainActivity
import com.s0undplus.tv.Screenshots
import com.s0undplus.tv.SignedInRule
import com.s0undplus.tv.data.AdMode
import com.s0undplus.tv.dump
import com.s0undplus.tv.fake.FakeStreams
import com.s0undplus.tv.fake.StreamsRule
import com.s0undplus.tv.waitForNoNode
import com.s0undplus.tv.waitForNode
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private val AD_TEXT = hasText("Ad break", substring = true)

/**
 * The real player (ExoPlayer, the app's HLS handling, the AdBlocker) on a TV emulator, playing a fake live channel made
 * of tiny real video segments. The ad markers are synthetic; this shows the app reacts to them, not that Twitch's
 * real ads look like this.
 */
@RunWith(AndroidJUnit4::class)
class PlayerPlaybackTest {
    @get:Rule(order = 0) val hermetic = HermeticRule()
    @get:Rule(order = 1) val streams = StreamsRule()
    @get:Rule(order = 2) val signedIn = SignedInRule()
    @get:Rule(order = 3) val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 99) val capture = FailureCapture { compose.dump(6000) }

    @Test
    fun theLiveStreamPlaysWithNoErrorAndNoAdNotice() {
        compose.openAlphaStream()
        compose.awaitSegments("live", 4)
        compose.pause(3_000)
        Screenshots.capture("player-playing")
        assertTrue("an error screen showed", compose.onAllNodes(hasText("Try again")).fetchSemanticsNodes().isEmpty())
        assertTrue("an ad notice showed with no ad", compose.onAllNodes(AD_TEXT).fetchSemanticsNodes().isEmpty())
        // It is a live stream: segments keep being fetched as time passes.
        val before = segmentsFetched("live")
        compose.awaitSegments("live", 2, since = before)
    }
}

@RunWith(AndroidJUnit4::class)
class PlayerHideAdsTest {
    @get:Rule(order = 0) val hermetic = HermeticRule()
    @get:Rule(order = 1) val streams = StreamsRule()
    @get:Rule(order = 2) val signedIn = SignedInRule { it.copy(adMode = AdMode.HIDE) }
    @get:Rule(order = 3) val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 99) val capture = FailureCapture { compose.dump(6000) }

    @Test
    fun anAdBreakIsHiddenAndTheStreamComesBackWhenItEnds() {
        compose.openAlphaStream()
        compose.awaitSegments("live", 4)
        compose.pause(2_000)

        FakeStreams.startAd()
        compose.waitForNode(hasText("Ad break — hidden", substring = true), timeoutMs = 60_000, description = "the ad-hidden notice")
        Screenshots.capture("player-ad-hidden")

        FakeStreams.endAd()
        compose.waitForNoNode(AD_TEXT, timeoutMs = 60_000, description = "the ad notice to go away")
        val before = segmentsFetched("live")
        compose.awaitSegments("live", 2, since = before)
        Screenshots.capture("player-after-ad")
    }
}

@RunWith(AndroidJUnit4::class)
class PlayerSwapAdsTest {
    @get:Rule(order = 0) val hermetic = HermeticRule()
    @get:Rule(order = 1) val streams = StreamsRule()
    @get:Rule(order = 2) val signedIn = SignedInRule { it.copy(adMode = AdMode.VIDEO_SWAP) }
    @get:Rule(order = 3) val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 99) val capture = FailureCapture { compose.dump(6000) }

    @Test
    fun anAdBreakIsReplacedByTheAdFreeCopyAndTheLiveStreamComesBackWhenItEnds() {
        compose.openAlphaStream()
        compose.awaitSegments("live", 4)
        compose.pause(2_000)

        FakeStreams.startAd()
        compose.waitForNode(hasText("Ad break — playing an ad-free copy"), timeoutMs = 60_000, description = "the ad-free-copy notice")
        assertTrue("the ad-free copy was asked for with the Android platform", FakeStreams.masterRequests.any { it.endsWith("android") })
        compose.awaitSegments("alt", 2)
        Screenshots.capture("player-ad-swapped")

        FakeStreams.endAd()
        compose.waitForNoNode(AD_TEXT, timeoutMs = 60_000, description = "the ad notice to go away")
        val before = segmentsFetched("live")
        compose.awaitSegments("live", 2, since = before)
        Screenshots.capture("player-after-swap")
    }
}
