package com.s0undplus.twitch.update

import com.s0undplus.twitch.TwitchApiException
import com.s0undplus.twitch.json
import com.s0undplus.twitch.testHttp
import com.s0undplus.twitch.text
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer

class UpdateCheckerTest {
    private lateinit var server: MockWebServer

    @BeforeTest
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @AfterTest
    fun tearDown() = server.close()

    private fun checker() = UpdateChecker(testHttp(), "me/app", server.url("/").toString().trimEnd('/'))

    @Test
    fun comparesVersionsNumericallyNotAlphabetically() {
        assertTrue(Versions.isNewer("1.10.0", "1.9.9"))
        assertTrue(Versions.isNewer("v2.0", "1.99.99"))
        assertEquals(0, Versions.compare("v1.2", "1.2.0"))
        assertFalse(Versions.isNewer("1.2.0", "1.2.0"))
        assertFalse(Versions.isNewer("1.0.0", "1.0.1"))
    }

    @Test
    fun preReleaseSortsBelowItsRelease() {
        assertTrue(Versions.isNewer("1.2.0", "1.2.0-beta.1"))
        assertFalse(Versions.isNewer("1.2.0-beta.1", "1.2.0"))
        assertTrue(Versions.isNewer("1.3.0-beta", "1.2.0"))
        assertEquals(0, Versions.compare("garbage", "0"))
    }

    @Test
    fun findsTheApkAssetInTheLatestRelease() = runBlocking {
        server.enqueue(
            json(
                """{"tag_name":"v1.4.0","name":"S0undPlus 1.4.0","body":"- new stuff","draft":false,"prerelease":false,
                "assets":[{"name":"notes.txt","browser_download_url":"https://x/notes.txt","size":10},
                          {"name":"S0undPlus-v1.4.0.apk","browser_download_url":"https://x/app.apk","size":11000000}]}""",
            ),
        )
        val info = checker().latest()!!
        assertEquals("1.4.0", info.version)
        assertEquals("v1.4.0", info.tag)
        assertEquals("https://x/app.apk", info.apkUrl)
        assertEquals(11_000_000L, info.sizeBytes)
        assertEquals("- new stuff", info.notes)
        assertEquals("/repos/me/app/releases/latest", server.takeRequest().url.encodedPath)
    }

    @Test
    fun newerThanFiltersByCurrentVersion() = runBlocking {
        val body = """{"tag_name":"v1.4.0","assets":[{"name":"a.apk","browser_download_url":"https://x/a.apk","size":1}]}"""
        server.enqueue(json(body))
        assertNull(checker().newerThan("1.4.0"))
        server.enqueue(json(body))
        assertEquals("1.4.0", checker().newerThan("1.3.9")?.version)
    }

    @Test
    fun noReleaseDraftPrereleaseOrApkMeansNoUpdate() = runBlocking {
        server.enqueue(text("""{"message":"Not Found"}""", 404))
        assertNull(checker().latest())
        server.enqueue(json("""{"tag_name":"v9","draft":true,"assets":[{"name":"a.apk","browser_download_url":"u"}]}"""))
        assertNull(checker().latest())
        server.enqueue(json("""{"tag_name":"v9","prerelease":true,"assets":[{"name":"a.apk","browser_download_url":"u"}]}"""))
        assertNull(checker().latest())
        server.enqueue(json("""{"tag_name":"v9","assets":[{"name":"notes.txt","browser_download_url":"u"}]}"""))
        assertNull(checker().latest())
        assertNull(UpdateChecker(testHttp(), "").latest()) // repo not configured: no network call
    }

    @Test
    fun serverErrorsSurface() = runBlocking {
        server.enqueue(text("rate limited", 403))
        val e = assertFailsWith<TwitchApiException> { checker().latest() }
        assertEquals(403, e.status)
    }
}
