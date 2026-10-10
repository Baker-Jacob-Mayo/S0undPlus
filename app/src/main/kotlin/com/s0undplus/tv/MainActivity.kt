package com.s0undplus.tv

import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.s0undplus.tv.ui.theme.Palette
import com.s0undplus.tv.ui.theme.S0undPlusTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val bridge = ActivityBridge()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as S0undPlusApp).container

        bridge.pipSupported = packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        bridge.enterPip = ::enterPip
        addOnPictureInPictureModeChangedListener { info -> bridge.inPip = info.isInPictureInPictureMode }

        setContent {
            CompositionLocalProvider(LocalAppContainer provides container, LocalActivityBridge provides bridge) {
                S0undPlusTheme {
                    Box(Modifier.fillMaxSize().background(Palette.Background)) { AppRoot() }
                }
            }
        }

        // Android 12+ can shrink to PiP automatically when the user presses Home during playback.
        lifecycleScope.launch {
            snapshotFlow { bridge.pipEligible }.collect { eligible ->
                if (bridge.pipSupported) {
                    try {
                        setPictureInPictureParams(pipParams(autoEnter = eligible))
                    } catch (_: IllegalStateException) {
                        // not allowed right now; harmless
                    }
                }
            }
        }
    }

    /** Android 11 and below have no auto-enter, so Home ("user leave hint") has to trigger PiP by hand. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (bridge.pipEligible && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) enterPip()
    }

    private fun enterPip() {
        if (!bridge.pipSupported) return
        try {
            enterPictureInPictureMode(pipParams(autoEnter = bridge.pipEligible))
        } catch (_: IllegalStateException) {
            // PiP disabled in system settings or activity not in a state that allows it
        }
    }

    private fun pipParams(autoEnter: Boolean): PictureInPictureParams =
        PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setAutoEnterEnabled(autoEnter) }
            .build()
}
