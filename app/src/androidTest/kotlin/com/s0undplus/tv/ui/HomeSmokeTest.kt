package com.s0undplus.tv.ui

import android.view.KeyEvent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.s0undplus.tv.FailureCapture
import com.s0undplus.tv.HermeticRule
import com.s0undplus.tv.MainActivity
import com.s0undplus.tv.dump
import com.s0undplus.tv.inNavRail
import com.s0undplus.tv.waitForNode
import com.s0undplus.tv.waitForText
import com.s0undplus.tv.Screenshots
import com.s0undplus.tv.SignedInRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The whole app on a TV emulator, signed in as a fake viewer, fed by the fake Twitch. */
@RunWith(AndroidJUnit4::class)
class HomeSmokeTest {
    @get:Rule(order = 0) val hermetic = HermeticRule()
    @get:Rule(order = 1) val signedIn = SignedInRule()
    @get:Rule(order = 2) val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 99) val capture = FailureCapture { compose.dump(8000) }

    private fun waitForText(text: String) = compose.waitForText(text)

    private fun press(keyCode: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(keyCode)
        compose.waitForIdle()
    }

    @Test
    fun followingTabShowsTheLiveStreamsTheFakeServerReturned() {
        waitForText("Live now")
        waitForText("Alpha")
        waitForText("Beta")
        compose.onNodeWithText("Alpha stream title").assertExists()
        Screenshots.capture("home-following")
    }

    @Test
    fun launchFocusesTheFirstStreamAndLeftAndRightGoToTheNavigationAndBack() {
        waitForText("Alpha")
        compose.waitForNode(hasText("Alpha") and isFocused(), description = "the first live card, focused at launch")
        Screenshots.capture("home-card-focused")

        press(KeyEvent.KEYCODE_DPAD_LEFT)
        compose.waitForNode(hasText("Following") and isFocused() and inNavRail, description = "Following in the navigation")
        Screenshots.capture("home-nav-open")

        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.waitForNode(hasText("Alpha") and isFocused(), description = "back on the card that was focused")
    }
}
