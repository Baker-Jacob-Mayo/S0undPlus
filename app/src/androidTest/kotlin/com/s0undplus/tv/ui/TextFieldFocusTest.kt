package com.s0undplus.tv.ui

import android.view.KeyEvent
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.s0undplus.tv.FailureCapture
import com.s0undplus.tv.HermeticRule
import com.s0undplus.tv.MainActivity
import com.s0undplus.tv.Screenshots
import com.s0undplus.tv.SignedInRule
import com.s0undplus.tv.awaitCondition
import com.s0undplus.tv.dump
import com.s0undplus.tv.openSettingsTab
import com.s0undplus.tv.pressAndPump
import com.s0undplus.tv.pressDown
import com.s0undplus.tv.pressUntilFocused
import com.s0undplus.tv.pump
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A remote has no keyboard, so landing on a text field with the D-pad must not throw the on-screen keyboard over the
 * list: it opens when the viewer chooses to type (center button), and Back closes it again.
 */
@RunWith(AndroidJUnit4::class)
class TextFieldFocusTest {
    @get:Rule(order = 0) val hermetic = HermeticRule()
    @get:Rule(order = 1) val signedIn = SignedInRule()
    @get:Rule(order = 2) val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 99) val capture = FailureCapture { compose.dump(8000) }

    private val blockedWordsRow = hasText("e.g. spoiler, giveaway")

    private fun imeVisible(): Boolean {
        val decor = compose.activity.window.decorView
        return ViewCompat.getRootWindowInsets(decor)?.isVisible(WindowInsetsCompat.Type.ime()) == true
    }

    private fun dpadOntoTheBlockedWordsField() {
        compose.openSettingsTab()
        compose.pressDown()
        compose.pressUntilFocused(KeyEvent.KEYCODE_DPAD_DOWN, blockedWordsRow, maxPresses = 30)
    }

    @Test
    fun landingOnATextFieldWithTheDpadDoesNotOpenTheKeyboard() {
        dpadOntoTheBlockedWordsField()
        val sawKeyboard = (1..20).any {
            Thread.sleep(100)
            imeVisible()
        }
        Screenshots.capture("textfield-after-dpad-focus")
        assertTrue("the on-screen keyboard opened just from D-pad focus", !sawKeyboard)
        assertTrue("the window lost focus", compose.activity.window.decorView.hasWindowFocus())
        assertEquals(0, compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size) // a row, not an editor
    }

    @Test
    fun centerStartsEditingWithTheKeyboardAndBackLeavesEditingAgain() {
        dpadOntoTheBlockedWordsField()
        compose.mainClock.autoAdvance = false // an editor's blinking cursor would keep compose "busy" for ever

        compose.pressAndPump(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitCondition("the keyboard opens when editing starts") { compose.pump(100); imeVisible() }
        assertEquals("one editor is showing", 1, compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size)
        Screenshots.capture("textfield-editing")

        compose.pressAndPump(KeyEvent.KEYCODE_BACK) // the first Back closes the keyboard only
        awaitCondition("the keyboard closes") { compose.pump(100); !imeVisible() }
        assertEquals("still editing after the keyboard closed", 1, compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size)

        compose.pressAndPump(KeyEvent.KEYCODE_BACK) // the second leaves editing, not the screen
        compose.pump(500)
        assertEquals("editing ended", 0, compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size)
        compose.onAllNodes(blockedWordsRow and isFocused()).fetchSemanticsNodes().let {
            assertTrue("focus came back to the field's row", it.isNotEmpty())
        }
    }
}
