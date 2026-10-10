package com.s0undplus.tv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.s0undplus.twitch.DeepLink

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

    /**
     * A link the app was opened with (notification, TV home row, twitch.tv link). It stays queued until the viewer is
     * signed in and the UI can act on it, then the UI clears it.
     */
    var pendingLink by mutableStateOf<DeepLink?>(null)

    /** Enters PiP now (set by the Activity). */
    var enterPip: () -> Unit = {}
}

val LocalActivityBridge = staticCompositionLocalOf { ActivityBridge() }
