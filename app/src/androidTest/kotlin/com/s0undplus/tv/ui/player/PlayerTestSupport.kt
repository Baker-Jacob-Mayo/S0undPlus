package com.s0undplus.tv.ui.player

import androidx.compose.ui.test.junit4.ComposeTestRule
import com.s0undplus.tv.fake.FakeStreams
import com.s0undplus.tv.pressDown
import com.s0undplus.tv.pressSelect
import com.s0undplus.tv.waitForText
import com.s0undplus.tv.waitRealTime

/** From Home, opens the first live card (Alpha) with the remote, which starts the player on the fake stream. */
internal fun ComposeTestRule.openAlphaStream() {
    waitForText("Alpha")
    pressDown() // from the tab row onto the first stream card
    pressSelect()
}

/** Waits (letting compose run) until the player has fetched at least [count] segments of the [kind] copy. */
internal fun ComposeTestRule.awaitSegments(kind: String, count: Int, timeoutMs: Long = 60_000, since: Int = 0) {
    fun fetched() = FakeStreams.segmentRequests.count { it.startsWith("$kind/") } - since
    if (!waitRealTime(timeoutMs) { fetched() >= count }) {
        throw AssertionError(
            "The player fetched only ${fetched()} of the $count expected \"$kind\" segments in ${timeoutMs}ms. " +
                "Segments: ${FakeStreams.segmentRequests.toList().takeLast(20)}; masters: ${FakeStreams.masterRequests.toList()}",
        )
    }
}

internal fun segmentsFetched(kind: String): Int = FakeStreams.segmentRequests.count { it.startsWith("$kind/") }

/** Lets the app run for [ms] of real time (compose included), e.g. so a few more frames are drawn. */
internal fun ComposeTestRule.pause(ms: Long) {
    waitRealTime(ms) { false }
}
