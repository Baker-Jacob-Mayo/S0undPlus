package com.s0undplus.tv.ui

import android.view.KeyEvent
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.s0undplus.tv.FailureCapture
import com.s0undplus.tv.HermeticRule
import com.s0undplus.tv.MainActivity
import com.s0undplus.tv.Screenshots
import com.s0undplus.tv.SignedInRule
import com.s0undplus.tv.awaitCondition
import com.s0undplus.tv.dump
import com.s0undplus.tv.inNavRail
import com.s0undplus.tv.press
import com.s0undplus.tv.pressBack
import com.s0undplus.tv.pressDown
import com.s0undplus.tv.pressSelect
import com.s0undplus.tv.pressUntilFocused
import com.s0undplus.tv.testContainer
import com.s0undplus.tv.waitForNoNode
import com.s0undplus.tv.waitForNode
import com.s0undplus.tv.waitForText
import com.s0undplus.twitch.TwitchLanguages
import com.s0undplus.twitch.api.StreamFilter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Live's filters as a viewer uses them with the remote, checked against what is stored. */
@RunWith(AndroidJUnit4::class)
class LiveFiltersTest {
    @get:Rule(order = 0) val hermetic = HermeticRule()
    @get:Rule(order = 1) val signedIn = SignedInRule()
    @get:Rule(order = 2) val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 99) val capture = FailureCapture { compose.dump(8000) }

    private fun stored(): StreamFilter = runBlocking { testContainer.settings.liveFilter.first() }
    private val sheet = hasTestTag("languageSheet")

    /** Left onto the navigation, Down to Live, OK; then Up from the first card and along to the Language pill, OK. */
    private fun openLanguageSheet() {
        compose.waitForText("Following")
        compose.pressUntilFocused(KeyEvent.KEYCODE_DPAD_LEFT, inNavRail, maxPresses = 12)
        compose.pressUntilFocused(KeyEvent.KEYCODE_DPAD_DOWN, hasText("Live") and inNavRail, maxPresses = 8)
        compose.pressSelect()
        compose.waitForText("Gamma") // the fake's third live stream: the Live list is in
        // Up lands on the controls row (Refresh or Sort, whichever is nearer), then along to Language.
        compose.pressUntilFocused(KeyEvent.KEYCODE_DPAD_UP, hasText("Sort") or hasContentDescription("Refresh"), maxPresses = 4)
        compose.pressUntilFocused(KeyEvent.KEYCODE_DPAD_RIGHT, hasText("Language"), maxPresses = 3)
        compose.pressSelect()
        compose.waitForNode(sheet, description = "the language list")
    }

    @Test
    fun theLanguageListKeepsTheDpadInsideItAndBackWithoutChangesKeepsTheFilter() {
        assertEquals(StreamFilter(languages = setOf("en")), stored())
        openLanguageSheet()
        compose.waitForNode(hasText("All languages") and isFocused() and hasAnyAncestor(sheet), description = "focus on All languages")

        // More presses than there are rows, in each direction: focus may stop at an end but must never leave.
        val presses = TwitchLanguages.ALL.size + 10
        repeat(presses) {
            compose.press(KeyEvent.KEYCODE_DPAD_UP)
            compose.onNode(isFocused()).assert(hasAnyAncestor(sheet))
        }
        repeat(presses) {
            compose.press(KeyEvent.KEYCODE_DPAD_DOWN)
            compose.onNode(isFocused()).assert(hasAnyAncestor(sheet))
        }
        compose.onNode(isFocused()).assert(hasText(TwitchLanguages.ALL.last().second))
        Screenshots.capture("live-language-list-bottom")

        compose.pressBack()
        compose.waitForNoNode(sheet, description = "the language list")
        // Back only closed the list, focus went back to its pill, and nothing changed.
        compose.waitForNode(hasText("Language") and isFocused(), description = "focus back on the Language pill")
        assertEquals(StreamFilter(languages = setOf("en")), stored())
    }

    @Test
    fun tickingASecondLanguageStoresBothWhenTheListCloses() {
        openLanguageSheet()
        compose.pressDown(2) // past English (ticked) onto Español
        compose.pressSelect()
        compose.pressBack()
        compose.waitForNoNode(sheet, description = "the language list")
        awaitCondition("English and Spanish stored") { stored().languages == setOf("en", "es") }
        compose.waitForNode(hasText("English, Español"), description = "both languages on the pill")
    }
}
