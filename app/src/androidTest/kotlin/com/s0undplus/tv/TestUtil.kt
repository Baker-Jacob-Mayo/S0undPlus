package com.s0undplus.tv

import android.view.KeyEvent
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
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

/**
 * Waits up to [timeoutMs] of *real* time (letting compose run) for [condition]. compose's own `waitUntil` timeout is
 * counted on the test clock, which runs several times faster than the wall clock while waiting, so a "60 second" wait
 * could be over in ten: not enough for anything that really takes time, like an ad break passing through a player.
 */
fun ComposeTestRule.waitRealTime(timeoutMs: Long, condition: () -> Boolean): Boolean {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    waitUntil(timeoutMillis = 30 * 60_000L) { condition() || System.nanoTime() >= deadline }
    return condition()
}

fun ComposeTestRule.waitForNode(matcher: SemanticsMatcher, timeoutMs: Long = 30_000, description: String = matcher.description) {
    val found = try {
        waitRealTime(timeoutMs) { onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
    } catch (e: Throwable) {
        throw AssertionError("Failed while waiting for $description: ${e.javaClass.simpleName}. Screen:\n${dump()}", e)
    }
    if (!found) throw AssertionError("Timed out after ${timeoutMs}ms waiting for $description. Screen:\n${dump()}")
}

fun ComposeTestRule.waitForNoNode(matcher: SemanticsMatcher, timeoutMs: Long = 10_000, description: String = matcher.description) {
    val gone = try {
        waitRealTime(timeoutMs) { onAllNodes(matcher).fetchSemanticsNodes().isEmpty() }
    } catch (e: Throwable) {
        throw AssertionError("Failed while waiting for $description to go away: ${e.javaClass.simpleName}. Screen:\n${dump()}", e)
    }
    if (!gone) throw AssertionError("Timed out after ${timeoutMs}ms waiting for $description to go away. Screen:\n${dump()}")
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

/** The navigation rail down the left of Home, and anything inside it. */
val inNavRail: SemanticsMatcher = hasAnyAncestor(hasTestTag("navRail"))

/**
 * Opens Settings with the remote, the way a viewer would: Left from the page onto the navigation rail, Down to
 * Settings, OK. Settings then has its first row focused.
 */
fun ComposeTestRule.openSettingsTab() {
    waitForText("Following")
    pressUntilFocused(KeyEvent.KEYCODE_DPAD_LEFT, inNavRail, maxPresses = 12)
    pressUntilFocused(KeyEvent.KEYCODE_DPAD_DOWN, hasText("Settings") and inNavRail, maxPresses = 8)
    pressSelect()
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
