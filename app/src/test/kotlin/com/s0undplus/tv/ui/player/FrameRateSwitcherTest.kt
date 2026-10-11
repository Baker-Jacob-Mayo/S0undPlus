package com.s0undplus.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameRateSwitcherTest {
    /** The refresh rates an onn 4K Pro offers at 4K (measured 2026-10-11). */
    private val onn = listOf(59.94006f, 60.000004f, 50f, 29.97003f, 30.000002f, 25f, 23.976025f, 24.000002f)

    private fun pick(current: Float, fps: Float) = FrameRateSwitcher.pickRate(current, onn, fps)

    @Test
    fun streamsThatAlreadyFitSixtyHertzCauseNoSwitch() {
        assertNull(pick(60.000004f, 60f))
        assertNull(pick(60.000004f, 30f))
    }

    @Test
    fun fiftyAndTwentyFiveFpsGoToFiftyHertz() {
        assertEquals(50f, pick(60.000004f, 50f))
        assertEquals(50f, pick(60.000004f, 25f)) // not 25 Hz: the menus would crawl
    }

    @Test
    fun filmRatesGoToTheirOwnRate() {
        assertEquals(24.000002f, pick(60.000004f, 24f))
        assertEquals(23.976025f, pick(60.000004f, 23.976f))
    }

    @Test
    fun afterAFiftyHertzStreamASixtyFpsOneGoesBackToSixty() {
        assertEquals(60.000004f, pick(50f, 60f))
        assertEquals(60.000004f, pick(50f, 30f)) // the lowest fitting rate of at least 48 Hz
    }

    @Test
    fun ntscAndWholeRatesAreToldApart() {
        assertTrue(FrameRateSwitcher.fits(59.94006f, 29.97f))
        assertFalse(FrameRateSwitcher.fits(60.000004f, 29.97f))
        assertEquals(59.94006f, pick(60.000004f, 29.97f))
    }

    @Test
    fun anUnknownFrameRateChangesNothing() {
        assertNull(pick(60f, 0f))
        assertNull(FrameRateSwitcher.pickRate(60f, listOf(60f), 50f)) // nothing fits: stay put
    }
}
