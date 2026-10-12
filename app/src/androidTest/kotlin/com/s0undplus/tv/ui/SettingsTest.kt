package com.s0undplus.tv.ui

import android.view.KeyEvent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.s0undplus.tv.FailureCapture
import com.s0undplus.tv.HermeticRule
import com.s0undplus.tv.MainActivity
import com.s0undplus.tv.dump
import com.s0undplus.tv.Screenshots
import com.s0undplus.tv.SignedInRule
import com.s0undplus.tv.awaitCondition
import com.s0undplus.tv.data.AdMode
import com.s0undplus.tv.openSettingsTab
import com.s0undplus.tv.pressAndPump
import com.s0undplus.tv.pump
import com.s0undplus.tv.pressDown
import com.s0undplus.tv.pressUntilFocused
import com.s0undplus.tv.testContainer
import com.s0undplus.tv.waitForNode
import com.s0undplus.tv.waitForText
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Settings as a viewer uses them: reached and driven with the remote, checked against what is actually stored. */
@RunWith(AndroidJUnit4::class)
class SettingsTest {
    @get:Rule(order = 0) val hermetic = HermeticRule()
    @get:Rule(order = 1) val signedIn = SignedInRule()
    @get:Rule(order = 2) val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 99) val capture = FailureCapture { compose.dump(8000) }

    private fun stored() = runBlocking { testContainer.settings.settings.first() }
    @Test
    fun aToggleIsSavedAndTheSecondPressUndoesIt() {
        compose.openSettingsTab()
        compose.onNodeWithText("Show clock").performClick()
        awaitCondition("show clock stored as on") { stored().showClock }
        compose.onNodeWithText("Show clock").performClick()
        awaitCondition("show clock stored as off") { !stored().showClock }
    }

    @Test
    fun aTypedBlockedWordIsSavedAndStillThereAfterScrollingAwayAndBack() {
        compose.openSettingsTab()
        compose.pressDown()
        compose.pressUntilFocused(KeyEvent.KEYCODE_DPAD_DOWN, hasText("e.g. spoiler, giveaway"), maxPresses = 30)

        // While a field is being edited its blinking cursor keeps compose "busy" under the test clock: drive time by hand.
        compose.mainClock.autoAdvance = false
        compose.pressAndPump(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("spoiler")
        compose.pump(1_500) // past the 500 ms save delay, which runs on compose's clock
        awaitCondition("the blocked word is stored") { stored().blockedWords == "spoiler" }
        compose.pressAndPump(KeyEvent.KEYCODE_BACK) // closes the keyboard
        compose.pressAndPump(KeyEvent.KEYCODE_BACK) // leaves editing
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        // Far enough away that the field leaves composition, then back: it must show what was saved, not the value it
        // was first built with (the regression the "saveable" state fixed).
        val list = compose.onNode(hasScrollToNodeAction())
        list.performScrollToNode(hasText("About"))
        compose.waitForIdle()
        list.performScrollToNode(hasText("Blocked words (comma separated)"))
        compose.waitForNode(hasText("spoiler"), description = "the saved word shown in its field after scrolling back")
        assertEquals("spoiler", stored().blockedWords)
    }

    @Test
    fun adHandlingStepsThroughTheModesAndShowsTheProxyListInProxyMode() {
        compose.openSettingsTab()
        val list = compose.onNode(hasScrollToNodeAction())
        list.performScrollToNode(hasText("Ad handling"))
        assertEquals(AdMode.OFF, stored().adMode)
        compose.onNodeWithText("Ad handling").performClick() // Off -> Hide
        awaitCondition("ad mode Hide stored") { stored().adMode == AdMode.HIDE }
        compose.waitForText("Hide ads (mute and black out)")
        compose.onNodeWithText("Ad handling").performClick() // Hide -> proxy URLs
        awaitCondition("ad mode proxy URLs stored") { stored().adMode == AdMode.PROXY }
        list.performScrollToNode(hasText("eu.luminous.dev"))
        compose.onNodeWithText("eu.luminous.dev").assertIsDisplayed()
        Screenshots.capture("settings-ads-proxy-mode")
    }
}
