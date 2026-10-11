package com.s0undplus.tv

import android.view.KeyEvent
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.test.platform.app.InstrumentationRegistry

/** A remote-control key press, delivered through the window the way a real remote would. */
fun ComposeTestRule.press(keyCode: Int) {
    InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(keyCode)
    waitForIdle()
}

fun ComposeTestRule.pressRight(times: Int = 1) = repeat(times) { press(KeyEvent.KEYCODE_DPAD_RIGHT) }
fun ComposeTestRule.pressDown(times: Int = 1) = repeat(times) { press(KeyEvent.KEYCODE_DPAD_DOWN) }
fun ComposeTestRule.pressUp(times: Int = 1) = repeat(times) { press(KeyEvent.KEYCODE_DPAD_UP) }
fun ComposeTestRule.pressSelect() = press(KeyEvent.KEYCODE_DPAD_CENTER)
fun ComposeTestRule.pressBack() = press(KeyEvent.KEYCODE_BACK)

/** The semantics tree as text, for a failure message: what was actually on screen. */
fun ComposeTestRule.dump(maxChars: Int = 3500): String =
    try {
        onRoot().printToString().take(maxChars)
    } catch (t: Throwable) {
        "(could not print the tree: $t)"
    }

fun ComposeTestRule.waitForText(text: String, timeoutMs: Long = 30_000) = waitForNode(hasText(text), timeoutMs, "text \"$text\"")

fun ComposeTestRule.waitForNode(matcher: SemanticsMatcher, timeoutMs: Long = 30_000, description: String = matcher.description) {
    try {
        waitUntil(timeoutMillis = timeoutMs) { onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
    } catch (e: Throwable) {
        throw AssertionError("Timed out after ${timeoutMs}ms waiting for $description. Screen:\n${dump()}", e)
    }
}

fun ComposeTestRule.waitForNoNode(matcher: SemanticsMatcher, timeoutMs: Long = 10_000, description: String = matcher.description) {
    try {
        waitUntil(timeoutMillis = timeoutMs) { onAllNodes(matcher).fetchSemanticsNodes().isEmpty() }
    } catch (e: Throwable) {
        throw AssertionError("Timed out after ${timeoutMs}ms waiting for $description to go away. Screen:\n${dump()}", e)
    }
}

/** Presses [keyCode] until the focused node matches [target], at most [maxPresses] times. */
fun ComposeTestRule.pressUntilFocused(keyCode: Int, target: SemanticsMatcher, maxPresses: Int = 12) {
    repeat(maxPresses) {
        if (onAllNodes(target and isFocused()).fetchSemanticsNodes().isNotEmpty()) return
        press(keyCode)
    }
    if (onAllNodes(target and isFocused()).fetchSemanticsNodes().isEmpty()) {
        throw AssertionError("Focus never reached ${target.description} in $maxPresses presses. Screen:\n${dump()}")
    }
}

/** Opens the Settings tab of Home with the remote: Right from the first tab, six times. */
fun ComposeTestRule.openSettingsTab() {
    waitForText("Following")
    pressRight(6)
    waitForText("Show clock")
}

/** Polls (on the test thread) until [condition] holds; the failure message says what was being waited for. */
fun awaitCondition(description: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    while (System.nanoTime() < deadline) {
        if (condition()) return
        Thread.sleep(50)
    }
    if (!condition()) throw AssertionError("Timed out after ${timeoutMs}ms waiting for: $description")
}

/**
 * While a text field is being edited its blinking cursor keeps compose "busy" for ever under the test clock, so the
 * tests that edit take the clock into their own hands: set `mainClock.autoAdvance = false`, then use these.
 */
fun ComposeTestRule.pump(ms: Long = 250) = mainClock.advanceTimeBy(ms)

/** A key press delivered without waiting for compose to go idle; time is then advanced by hand. */
fun ComposeTestRule.pressAndPump(keyCode: Int, ms: Long = 300) {
    InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(keyCode)
    pump(ms)
}
