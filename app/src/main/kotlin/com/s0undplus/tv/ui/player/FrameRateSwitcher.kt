package com.s0undplus.tv.ui.player

import android.app.Activity
import android.os.Build
import android.util.Log
import android.view.Display
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Puts the TV in a display mode whose refresh rate suits the stream, e.g. 50 Hz for a 50 fps stream, which on a 60 Hz
 * picture judders (every fifth frame is held twice). Streams that already fit (30 or 60 fps on 60 Hz) cause no switch.
 *
 * It asks for the mode through the window's preferred display mode, which the system honours even when its own
 * "match content frame rate" is set to "seamless only": on most TV boxes no switch between rates is seamless, so that
 * setting alone never switches. A switch blanks the screen for a second or two, so it happens once per stream and
 * never for adaptive-quality changes.
 */
class FrameRateSwitcher(private val activity: Activity) {
    private var appliedFor: String? = null

    /** Picks and requests a mode for [fps] (the stream's highest frame rate); [streamKey] makes it once per stream. */
    fun apply(streamKey: String, fps: Float) {
        if (fps <= 0f || appliedFor == streamKey) return
        appliedFor = streamKey
        val display = display() ?: return
        val target = pick(display.mode, display.supportedModes.toList(), fps)
        val modeId = if (target == null || target.modeId == display.mode.modeId) 0 else target.modeId
        Log.i(TAG, "stream at $fps fps on ${display.mode.refreshRate} Hz: " + if (modeId == 0) "no switch" else "switching to ${target?.refreshRate} Hz")
        setPreferredMode(modeId)
    }

    /** Back to the system's own choice (leaving the player, or the setting turned off). */
    fun reset() {
        appliedFor = null
        setPreferredMode(0)
    }

    private fun setPreferredMode(modeId: Int) {
        val attributes = activity.window.attributes
        if (attributes.preferredDisplayModeId == modeId) return
        attributes.preferredDisplayModeId = modeId
        activity.window.attributes = attributes
    }

    @Suppress("DEPRECATION")
    private fun display(): Display? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.display else activity.windowManager.defaultDisplay

    companion object {
        private const val TAG = "FrameRate"

        /**
         * The mode to use for [fps]: same resolution as [current], with a refresh rate that is a whole multiple of the
         * frame rate (so every frame is shown equally long), preferring the lowest such rate of at least 48 Hz so the
         * on-screen menus stay smooth. Null when nothing fits, or when [current] already does.
         */
        fun pick(current: Display.Mode, modes: List<Display.Mode>, fps: Float): Display.Mode? =
            pickRate(current.refreshRate, modes.filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }.map { it.refreshRate }, fps)
                ?.let { rate -> modes.first { it.refreshRate == rate && it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight } }

        /** [pick] on bare refresh rates (testable without Android's display classes). */
        fun pickRate(currentRate: Float, rates: List<Float>, fps: Float): Float? {
            if (fps <= 0f) return null
            if (fits(currentRate, fps)) return null
            val fitting = rates.filter { fits(it, fps) }
            return fitting.filter { it >= 47.5f }.minOrNull() ?: fitting.maxOrNull()
        }

        /** True when [rate] shows every frame of [fps] the same number of times (59.94 and 60 are told apart). */
        fun fits(rate: Float, fps: Float): Boolean {
            val multiple = (rate / fps).roundToInt()
            return multiple >= 1 && abs(rate - multiple * fps) / rate < 0.0005f
        }
    }
}
