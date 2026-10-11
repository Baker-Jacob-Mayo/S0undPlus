package com.s0undplus.tv.ui

import android.util.Log
import android.view.KeyEvent
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.s0undplus.tv.FailureCapture
import com.s0undplus.tv.HermeticRule
import com.s0undplus.tv.MainActivity
import com.s0undplus.tv.Screenshots
import com.s0undplus.tv.SignedInRule
import com.s0undplus.tv.dump
import com.s0undplus.tv.openSettingsTab
import com.s0undplus.tv.press
import com.s0undplus.tv.pressDown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A remote has no keyboard, so landing on a text field with the D-pad must not throw the on-screen keyboard over the
 * list: it should open when the viewer chooses to type (center button), not on the way past.
 */
@RunWith(AndroidJUnit4::class)
class TextFieldFocusTest {
    @get:Rule(order = 0) val hermetic = HermeticRule()
    @get:Rule(order = 1) val signedIn = SignedInRule()
    @get:Rule(order = 2) val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 99) val capture = FailureCapture { compose.dump(8000) }

    private fun imeVisible(): Boolean {
        val decor = compose.activity.window.decorView
        return ViewCompat.getRootWindowInsets(decor)?.isVisible(WindowInsetsCompat.Type.ime()) == true
    }

    private fun windowHasFocus(): Boolean = compose.activity.window.decorView.hasWindowFocus()

    /** Moves down the Settings list until a text field has focus, tolerating (and logging) a busy compose. */
    private fun dpadOntoTheFirstTextField() {
        compose.openSettingsTab()
        compose.pressDown()
        for (i in 1..40) {
            val onField = try {
                compose.onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().isNotEmpty()
            } catch (t: Throwable) {
                Log.e("TestLog", "compose was busy while looking for the focused field: ${t.javaClass.simpleName}")
                true
            }
            if (onField) return
            try {
                compose.press(KeyEvent.KEYCODE_DPAD_DOWN)
            } catch (t: Throwable) {
                Log.e("TestLog", "compose was busy after press #$i: ${t.javaClass.simpleName}")
                return
            }
        }
    }

    @Test
    fun landingOnATextFieldWithTheDpadDoesNotOpenTheKeyboardOrStealTheWindowFocus() {
        dpadOntoTheFirstTextField()
        val samples = ArrayList<Pair<Boolean, Boolean>>()
        repeat(30) {
            samples += windowHasFocus() to imeVisible()
            Thread.sleep(100)
        }
        Screenshots.capture("textfield-after-dpad-focus")
        val flips = samples.zipWithNext().count { (a, b) -> a != b }
        Log.e("TestLog", "(windowFocus, imeVisible) over 3 s: " + samples.distinct() + ", changes: $flips")
        assertTrue("the on-screen keyboard opened just from D-pad focus (samples: ${samples.distinct()})", samples.none { it.second })
        assertTrue("the window lost focus while the field was focused (samples: ${samples.distinct()})", samples.all { it.first })
        assertEquals("focus/keyboard state kept flipping", 0, flips)
    }

    /** A key press with no waiting on compose, which may be permanently "busy" while a text field is focused. */
    private fun raw(keyCode: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(keyCode)
        Thread.sleep(800)
    }

    private fun idleVerdict(): String = try {
        compose.waitForIdle()
        "idle"
    } catch (t: Throwable) {
        "BUSY"
    }

    private fun state(label: String) =
        Log.e("TestLog", "probe $label: windowFocus=${windowHasFocus()} ime=${imeVisible()} resumed=${compose.activity.lifecycle.currentState}")

    /** Not an assertion: records what the keyboard, Back and compose idling do around a focused text field. */
    @Test
    fun probeWhatTheKeyboardAndBackDoAroundATextField() {
        dpadOntoTheFirstTextField()
        state("after D-pad onto the field")
        compose.mainClock.autoAdvance = false
        Log.e("TestLog", "probe with autoAdvance=false: ${idleVerdict()}")
        compose.mainClock.autoAdvance = true
        raw(KeyEvent.KEYCODE_BACK)
        state("after one Back")
        Log.e("TestLog", "probe after one Back: ${idleVerdict()}")
        Screenshots.capture("probe-after-back")
        raw(KeyEvent.KEYCODE_DPAD_DOWN)
        state("after Down")
        Log.e("TestLog", "probe after Down: ${idleVerdict()}")
        Screenshots.capture("probe-after-down")
    }
}
