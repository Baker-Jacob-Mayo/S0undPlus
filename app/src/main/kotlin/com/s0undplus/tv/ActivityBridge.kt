package com.s0undplus.tv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The few things Compose screens and the Activity need to tell each other about Picture-in-Picture, which only the
 * Activity can control.
 */
class ActivityBridge {
    /** True while the app is shrunk into the PiP window; the player hides its overlays. */
    var inPip by mutableStateOf(false)

    /** True while a stream is on screen, so pressing Home should shrink to PiP instead of stopping playback. */
    var pipEligible by mutableStateOf(false)

    /** Whether this device supports PiP at all (set by the Activity). */
    var pipSupported by mutableStateOf(false)

    /** Enters PiP now (set by the Activity). */
    var enterPip: () -> Unit = {}
}

val LocalActivityBridge = staticCompositionLocalOf { ActivityBridge() }
