package com.s0undplus.tv.ui

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextRange
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.s0undplus.tv.FailureCapture
import com.s0undplus.tv.HermeticRule
import com.s0undplus.tv.MainActivity
import com.s0undplus.tv.SignedInRule
import com.s0undplus.tv.awaitCondition
import com.s0undplus.tv.data.AdMode
import com.s0undplus.tv.dump
import com.s0undplus.tv.openSettingsTab
import com.s0undplus.tv.pressAndPump
import com.s0undplus.tv.pressDown
import com.s0undplus.tv.pressUntilFocused
import com.s0undplus.tv.pump
import com.s0undplus.tv.testContainer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val PROXY_HINT = "e.g. proxy.example/live/\$channel"
private const val NEW_PROXY = "proxy.example/live/\$channel"

/** The details of editing a text field with a remote: what Back does, where the cursor starts, where focus goes after. */
@RunWith(AndroidJUnit4::class)
class TextFieldBehaviourTest {
    @get:Rule(order = 0) val hermetic = HermeticRule()
    @get:Rule(order = 1) val signedIn = SignedInRule { it.copy(adMode = AdMode.PROXY, blockedWords = "spoiler") }
    @get:Rule(order = 2) val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 99) val capture = FailureCapture { compose.dump(8000) }

    private fun stored() = runBlocking { testContainer.settings.settings.first() }

    private fun scrollTo(matcher: androidx.compose.ui.test.SemanticsMatcher) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(matcher)
    }

    @Test
    fun backWhileEditingTheAddProxyFieldLeavesEditingWithoutSubmittingIt() {
        compose.openSettingsTab()
        scrollTo(hasText(PROXY_HINT))
        compose.mainClock.autoAdvance = false // an editor's blinking cursor would keep compose "busy" for ever
        compose.onNodeWithText(PROXY_HINT).performClick() // starts editing
        compose.pump(500)
        assertEquals("editing started", 1, compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size)

        compose.pressAndPump(KeyEvent.KEYCODE_BACK) // closes the keyboard
        compose.pressAndPump(KeyEvent.KEYCODE_BACK) // leaves editing
        compose.pump(500)
        assertEquals("editing ended", 0, compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size)
        assertEquals(
            "backing out of an empty field must not complain that it is not an address",
            0,
            compose.onAllNodes(hasText("That needs to be an https address", substring = true)).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun typingStartsAfterTheTextThatIsAlreadyThere() {
        compose.openSettingsTab()
        compose.pressDown()
        compose.pressUntilFocused(KeyEvent.KEYCODE_DPAD_DOWN, hasText("spoiler"), maxPresses = 30)
        compose.mainClock.autoAdvance = false
        compose.pressAndPump(KeyEvent.KEYCODE_DPAD_CENTER) // starts editing
        val selection = compose.onAllNodes(hasSetTextAction())[0].fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
        assertEquals("the cursor starts after the existing text", TextRange("spoiler".length), selection)
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("X")
        compose.pump(1_500) // past the 500 ms save delay, which runs on compose's clock
        awaitCondition("the typed letter is stored after the old text") { stored().blockedWords == "spoilerX" }
    }

    @Test
    fun addingAProxyEndsEditingAndFocusComesBackToTheFieldItself() {
        compose.openSettingsTab()
        scrollTo(hasText(PROXY_HINT))
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText(PROXY_HINT).performClick()
        compose.pump(500)
        compose.onAllNodes(hasSetTextAction())[0].performTextReplacement(NEW_PROXY)
        compose.pump(200)
        compose.onAllNodes(hasSetTextAction())[0].performImeAction() // "Done" on the keyboard
        compose.pump(1_000)

        awaitCondition("the proxy was added") { stored().proxyEntries.any { it.template.contains("proxy.example") } }
        assertEquals("editing ended", 0, compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size)
        // The new entry pushed the field down the list; it is the same field (keyed), so focus finds its way back to it.
        assertTrue(
            "focus is back on the (now empty) field",
            compose.onAllNodes(hasText(PROXY_HINT) and isFocused()).fetchSemanticsNodes().isNotEmpty(),
        )
    }
}
