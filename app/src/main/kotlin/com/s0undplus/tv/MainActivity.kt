package com.s0undplus.tv

import android.Manifest
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.s0undplus.tv.background.LiveCheckScheduler
import com.s0undplus.tv.background.Notifier
import com.s0undplus.tv.background.TvChannels
import com.s0undplus.tv.ui.theme.Palette
import com.s0undplus.tv.ui.theme.S0undPlusTheme
import com.s0undplus.twitch.DeepLinkParser
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {
    private val bridge = ActivityBridge()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* the worker re-checks on each run */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as S0undPlusApp).container

        // Apply the saved theme before the first frame so a non-default theme doesn't flash purple.
        Palette.apply(runBlocking { container.settings.settings.first().theme })

        bridge.pipSupported = packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        bridge.enterPip = ::enterPip
        addOnPictureInPictureModeChangedListener { info -> bridge.inPip = info.isInPictureInPictureMode }
        handleLink(intent)

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

        // Settings drive the theme and the background checks.
        lifecycleScope.launch {
            container.settings.settings.collect { s ->
                Palette.apply(s.theme)
                LiveCheckScheduler.sync(applicationContext, s)
            }
        }
        lifecycleScope.launch {
            var first = true
            container.settings.settings.map { it.goLiveNotifications }.distinctUntilChanged().collect { on ->
                if (on && !first) {
                    if (!Notifier.hasPermission(this@MainActivity)) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    LiveCheckScheduler.sync(applicationContext, container.settings.settings.first(), runNow = true)
                }
                first = false
            }
        }
        lifecycleScope.launch {
            var first = true
            container.settings.settings.map { it.homeChannel }.distinctUntilChanged().collect { on ->
                when {
                    on && first -> TvChannels.ensure(applicationContext)
                    on -> {
                        TvChannels.requestBrowsable(this@MainActivity)
                        LiveCheckScheduler.sync(applicationContext, container.settings.settings.first(), runNow = true)
                    }
                    !first -> TvChannels.remove(applicationContext)
                }
                first = false
            }
        }
    }

    /** Whether the Back press now in progress started here (its key-down was taken by [dispatchKeyEvent]). */
    private var backDown = false

    /**
     * Back goes straight to the back handlers. Compose otherwise turns the Back key into "move focus to the
     * enclosing focusable" and consumes it whenever there is one, so closing a menu, the player controls or a screen
     * took two presses: the first only moved focus. The on-screen keyboard still gets Back first (it is a separate
     * window); Android 16 and later deliver Back this way anyway.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) backDown = true
            KeyEvent.ACTION_UP -> {
                if (backDown && !event.isCanceled) onBackPressedDispatcher.onBackPressed()
                backDown = false
            }
        }
        return true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLink(intent)
    }

    private fun handleLink(intent: Intent?) {
        val uri = intent?.data ?: return
        DeepLinkParser.parse(uri.scheme, uri.host, uri.pathSegments)?.let { bridge.pendingLink = it }
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
