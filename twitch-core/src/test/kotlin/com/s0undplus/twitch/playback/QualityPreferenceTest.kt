package com.s0undplus.twitch.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QualityPreferenceTest {
    private fun v(name: String, h: Int, fps: Double, bw: Long) =
        Variant(name, "https://x/$name", bw, h * 16 / 9, h, fps, "avc1")

    private val rungs = listOf(
        v("1080p60 (source)", 1080, 60.0, 8_000_000),
        v("720p60", 720, 60.0, 3_400_000),
        v("720p", 720, 30.0, 2_300_000),
        v("480p", 480, 30.0, 1_200_000),
        v("160p", 160, 30.0, 250_000),
        Variant("Audio only", "https://x/audio", 160_000, 0, 0, 0.0, "mp4a"),
    )

    @Test
    fun autoMeansAdaptive() {
        assertNull(QualityPreference.pick(rungs, QualityPreference.AUTO))
        assertNull(QualityPreference.pick(rungs, "garbage"))
        assertNull(QualityPreference.pick(emptyList(), "720"))
    }

    @Test
    fun sourcePicksTheSourceRung() {
        assertEquals("1080p60 (source)", QualityPreference.pick(rungs, QualityPreference.SOURCE)?.name)
        // No rung labelled source: fall back to the highest bandwidth, never audio-only.
        assertEquals("720p60", QualityPreference.pick(rungs.drop(1), QualityPreference.SOURCE)?.name)
    }

    @Test
    fun numericPreferenceMatchesHeightPreferringHigherFrameRate() {
        assertEquals("720p60", QualityPreference.pick(rungs, "720")?.name)
        assertEquals("480p", QualityPreference.pick(rungs, "480")?.name)
    }

    @Test
    fun missingHeightFallsBackToNextLowerThenSmallest() {
        assertEquals("160p", QualityPreference.pick(rungs, "360")?.name)
        assertEquals("160p", QualityPreference.pick(listOf(rungs[4]), "1080")?.name)
        assertEquals("160p", QualityPreference.pick(listOf(rungs[4]), "100")?.name) // nothing lower: smallest
    }
}
