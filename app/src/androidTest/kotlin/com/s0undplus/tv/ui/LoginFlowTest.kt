package com.s0undplus.tv.ui

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.s0undplus.tv.HermeticRule
import com.s0undplus.tv.MainActivity
import com.s0undplus.tv.Screenshots
import com.s0undplus.tv.SignedOutRule
import com.s0undplus.tv.awaitCondition
import com.s0undplus.tv.fake.FakeTwitch
import com.s0undplus.tv.testContainer
import com.s0undplus.tv.waitForNode
import com.s0undplus.tv.waitForText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

/** Twitch's device-code login as the fake plays it: a code to enter on a phone, then approval (or refusal). */
class FakeLogin : ExternalResource() {
    @Volatile var approved = false
    @Volatile var denied = false

    override fun before() {
        FakeTwitch.on("/oauth2/device") {
            FakeTwitch.json(
                """{"device_code":"dev-code","user_code":"ABCD1234","verification_uri":"https://www.twitch.tv/activate?device-code=ABCD1234","expires_in":600,"interval":1}""",
            )
        }
        FakeTwitch.on("/oauth2/token") {
            when {
                denied -> FakeTwitch.json("""{"status":400,"message":"access_denied"}""", 400)
                approved -> FakeTwitch.json(
                    """{"access_token":"new-access","refresh_token":"new-refresh","expires_in":14400,"scope":["user:read:follows"],"token_type":"bearer"}""",
                )
                else -> FakeTwitch.json("""{"status":400,"message":"authorization_pending"}""", 400)
            }
        }
        FakeTwitch.on("/oauth2/validate") {
            FakeTwitch.json("""{"client_id":"test-client-id","login":"newviewer","user_id":"1","scopes":["user:read:follows"],"expires_in":14000}""")
        }
    }
}

@RunWith(AndroidJUnit4::class)
class LoginFlowTest {
    @get:Rule(order = 0) val hermetic = HermeticRule()
    @get:Rule(order = 1) val login = FakeLogin()
    @get:Rule(order = 2) val signedOut = SignedOutRule()
    @get:Rule(order = 3) val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun showsTheCodeAndSignsInOnceTheViewerApprovesIt() {
        compose.waitForText("ABCD1234")
        compose.waitForText("twitch.tv/activate")
        Screenshots.capture("login-code")

        login.approved = true
        // The app polls every second; once approved it signs in and lands on Home, signed in as the validated login.
        compose.waitForText("Live now", timeoutMs = 30_000)
        compose.waitForText("newviewer")
        awaitCondition("the session holds the new login") { testContainer.session.tokens.value?.login == "newviewer" }
        assertEquals("1", testContainer.session.tokens.value?.userId)
        Screenshots.capture("login-then-home")
    }

    @Test
    fun aRefusedLoginSaysSoAndTryAgainAsksForANewCode() {
        login.denied = true
        compose.waitForText("Sign-in was cancelled on Twitch.", timeoutMs = 30_000)
        compose.waitForNode(hasText("Try again"))
        val before = FakeTwitch.requestsMatching("POST /oauth2/device").size
        compose.onNodeWithText("Try again").performClick()
        awaitCondition("a second device code was requested") { FakeTwitch.requestsMatching("POST /oauth2/device").size > before }
    }
}
