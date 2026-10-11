package com.s0undplus.tv

import android.app.Application
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.s0undplus.tv.data.AppSettings
import com.s0undplus.tv.fake.FakeTwitch
import com.s0undplus.twitch.auth.AuthTokens
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.rules.ExternalResource
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** The container the app under test is using (wired to [FakeTwitch] by [TestApp]). */
val testContainer: AppContainer
    get() = (ApplicationProvider.getApplicationContext<Application>() as S0undPlusApp).container

/** Starts every test from default answers and fails it afterwards if the app tried to reach anything but the fake. */
class HermeticRule : ExternalResource() {
    override fun before() {
        FakeTwitch.start()
        FakeTwitch.reset()
    }

    override fun after() {
        assertTrue(
            "The app tried to reach the real internet: ${FakeTwitch.blockedHosts.distinct()}",
            FakeTwitch.blockedHosts.isEmpty(),
        )
    }
}

/** Signs in a fake viewer (and resets settings) before the activity starts, and signs out afterwards. */
class SignedInRule(private val configure: (AppSettings) -> AppSettings = { it }) : ExternalResource() {
    override fun before() = runBlocking {
        testContainer.settings.update { configure(AppSettings(autoUpdateCheck = false)) }
        testContainer.session.signIn(
            AuthTokens(
                accessToken = "fake-access-token",
                refreshToken = "fake-refresh-token",
                expiresAtMillis = System.currentTimeMillis() + 24 * 3_600_000L,
                userId = "1",
                login = "tester",
                scopes = listOf("user:read:follows"),
            ),
        )
    }

    override fun after() = runBlocking {
        testContainer.session.signOut()
    }
}

/** Starts the activity with nobody signed in (so the login screen shows), and leaves nobody signed in afterwards. */
class SignedOutRule : ExternalResource() {
    override fun before() = runBlocking {
        testContainer.settings.update { AppSettings(autoUpdateCheck = false) }
        testContainer.session.signOut()
    }

    override fun after() = runBlocking {
        testContainer.session.signOut()
    }
}

/**
 * Declare this as the innermost rule (highest `order`) so it sees a failure while the activity is still on screen: it
 * saves a screenshot and logs what the fake server was asked and what the screen contained, none of which can be
 * recovered once the test is over and CI has thrown the emulator away.
 */
class FailureCapture(private val screenDump: () -> String) : TestWatcher() {
    override fun failed(e: Throwable?, description: Description) {
        val name = description.methodName
        Screenshots.capture("FAIL-$name")
        Log.e(TAG, "FAILED $name: ${e?.message?.lineSequence()?.firstOrNull()?.take(300)}")
        val seen = FakeTwitch.requests.toList()
        Log.e(TAG, "$name: the fake server saw ${seen.size} requests; the last ones: ${seen.takeLast(30).joinToString(" | ")}")
        Log.e(TAG, "$name: hosts the app tried that are not the fake: ${FakeTwitch.blockedHosts.distinct()}")
        for (line in screenDump().lineSequence().filter { it.isNotBlank() }.take(150)) Log.e(TAG, "$name: screen: ${line.take(200)}")
    }

    private companion object {
        const val TAG = "TestLog"
    }
}
