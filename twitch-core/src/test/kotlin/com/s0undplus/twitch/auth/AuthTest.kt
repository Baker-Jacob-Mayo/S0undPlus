package com.s0undplus.twitch.auth

import com.s0undplus.twitch.configFor
import com.s0undplus.twitch.json
import com.s0undplus.twitch.testHttp
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockWebServer

class AuthTest {
    private lateinit var server: MockWebServer

    @BeforeTest
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @AfterTest
    fun tearDown() = server.close()

    private val device = DeviceCode("dev-code", "ABCD1234", "https://www.twitch.tv/activate?device-code=ABCD1234", 600, 1)
    private val pending = """{"status":400,"message":"authorization_pending"}"""
    private val success =
        """{"access_token":"at-1","refresh_token":"rt-1","expires_in":3600,"scope":["user:read:follows"],"token_type":"bearer"}"""
    private val validate = """{"client_id":"test-client","login":"viewer","user_id":"777","scopes":["user:read:follows"],"expires_in":3500}"""

    private fun auth(now: () -> Long = { 1_000_000L }) = TwitchAuth(testHttp(), configFor(server), now)

    @Test
    fun requestsDeviceCodeWithClientIdAndScopes() = runTest {
        server.enqueue(
            json("""{"device_code":"d","user_code":"U","verification_uri":"https://www.twitch.tv/activate","expires_in":1800,"interval":5}"""),
        )
        val code = auth().requestDeviceCode(listOf("user:read:follows", "chat:read"))
        assertEquals("U", code.userCode)
        val request = server.takeRequest()
        assertEquals("/oauth2/device", request.url.encodedPath)
        val body = request.body!!.utf8()
        assertEquals(true, body.contains("client_id=test-client"))
        assertEquals(true, body.contains("scopes=user%3Aread%3Afollows+chat%3Aread"))
    }

    @Test
    fun pollsUntilApprovedThenValidates() = runTest {
        server.enqueue(json(pending, 400))
        server.enqueue(json(pending, 400))
        server.enqueue(json(success))
        server.enqueue(json(validate))

        val tokens = auth().awaitDeviceLogin(device, listOf("user:read:follows"))

        assertEquals("at-1", tokens.accessToken)
        assertEquals("rt-1", tokens.refreshToken)
        assertEquals("777", tokens.userId)
        assertEquals("viewer", tokens.login)
        assertEquals(1_000_000L + 3_600_000L, tokens.expiresAtMillis)
        val poll = server.takeRequest()
        assertEquals(true, poll.body!!.utf8().contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code"))
        repeat(2) { server.takeRequest() }
        val validateRequest = server.takeRequest()
        assertEquals("/oauth2/validate", validateRequest.url.encodedPath)
        assertEquals("OAuth at-1", validateRequest.headers["Authorization"])
    }

    @Test
    fun deniedLoginIsTerminal() = runTest {
        server.enqueue(json("""{"status":400,"message":"access_denied"}""", 400))
        assertFailsWith<DeviceLoginException.Denied> { auth().awaitDeviceLogin(device, listOf("x")) }
    }

    @Test
    fun expiredCodeIsTerminal() = runTest {
        server.enqueue(json("""{"status":400,"message":"invalid device code"}""", 400))
        assertFailsWith<DeviceLoginException.Expired> { auth().awaitDeviceLogin(device, listOf("x")) }
    }

    @Test
    fun refreshKeepsOldRefreshTokenIfServerOmitsIt() = runTest {
        server.enqueue(json("""{"access_token":"at-2","expires_in":100}"""))
        val old = AuthTokens("at-1", "rt-1", 0, "777", "viewer")
        val fresh = auth().refresh(old)
        assertEquals("at-2", fresh.accessToken)
        assertEquals("rt-1", fresh.refreshToken)
        assertEquals(1_000_000L + 100_000L, fresh.expiresAtMillis)
        val body = server.takeRequest().body!!.utf8()
        assertEquals(true, body.contains("grant_type=refresh_token"))
        assertEquals(true, body.contains("refresh_token=rt-1"))
    }

    @Test
    fun sessionRefreshesExpiringTokensAndSignsOutWhenRefreshIsRevoked() = runTest {
        var now = 1_000_000L
        val store = MemoryStore()
        val session = AuthSession(auth { now }, store) { now }
        session.signIn(AuthTokens("old", "rt", expiresAtMillis = now + 10_000, userId = "1", login = "v"))

        server.enqueue(json("""{"access_token":"new","refresh_token":"rt2","expires_in":3600}"""))
        assertEquals("new", session.freshTokens()?.accessToken) // < 60s left → refreshed
        assertEquals("rt2", store.saved?.refreshToken)

        now += 4_000_000L // new token now expired too
        server.enqueue(json("""{"status":400,"message":"Invalid refresh token"}""", 400))
        assertNull(session.freshTokens())
        assertNull(session.tokens.value)
        assertNull(store.saved)
    }

    private class MemoryStore : TokenStore {
        var saved: AuthTokens? = null
        override suspend fun load(): AuthTokens? = saved
        override suspend fun save(tokens: AuthTokens?) {
            saved = tokens
        }
    }
}
