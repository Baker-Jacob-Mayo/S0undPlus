package com.s0undplus.twitch.chat

import com.s0undplus.twitch.configFor
import com.s0undplus.twitch.testHttp
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest

class EmoteCacheTest {
    private lateinit var server: MockWebServer
    private val hits = ConcurrentHashMap<String, AtomicInteger>()
    private val failing = ConcurrentHashMap.newKeySet<String>()
    private var now = 0L
    private lateinit var cache: EmoteCache

    @BeforeTest
    fun setUp() {
        server = MockWebServer().also { it.start() }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                hits.getOrPut(path) { AtomicInteger() }.incrementAndGet()
                if (path in failing) return MockResponse.Builder().code(500).body("down").build()
                val body = when {
                    path == "/ffz/set/global" -> """{"sets":{},"default_sets":[]}"""
                    path == "/bttv/cached/emotes/global" -> """[{"id":"g1","code":"GlobalKappa","animated":false}]"""
                    path == "/7tv/emote-sets/global" -> """{"emotes":[]}"""
                    path.startsWith("/ffz/room/id/") -> """{"sets":{}}"""
                    path.startsWith("/bttv/cached/users/twitch/") ->
                        """{"channelEmotes":[{"id":"c-${path.substringAfterLast('/')}","code":"Chan${path.substringAfterLast('/')}"}],"sharedEmotes":[]}"""
                    path.startsWith("/7tv/users/twitch/") -> return MockResponse.Builder().code(404).body("""{"error":"not found"}""").build()
                    else -> return MockResponse.Builder().code(404).build()
                }
                return MockResponse.Builder().code(200).body(body).build()
            }
        }
        cache = EmoteCache(ThirdPartyEmotes(testHttp(), configFor(server)), ttlMs = 1_000, maxChannels = 2, nowMs = { now })
    }

    @AfterTest
    fun tearDown() = server.close()

    private fun requests() = hits.values.sumOf { it.get() }

    @Test
    fun theSecondLookupOfTheSameChannelMakesNoRequestsAtAll() = runTest {
        val first = cache.directory("10")
        assertNotNull(first["GlobalKappa"])
        assertNotNull(first["Chan10"])
        assertEquals(6, requests())

        val second = cache.directory("10")
        assertNotNull(second["Chan10"])
        assertEquals(6, requests())
    }

    @Test
    fun switchingChannelsOnlyFetchesThatChannelsOwnSets() = runTest {
        cache.directory("10")
        val before = requests()
        val other = cache.directory("11")
        assertNotNull(other["GlobalKappa"])
        assertNotNull(other["Chan11"])
        assertNull(other["Chan10"])
        assertEquals(before + 3, requests())
        assertEquals(1, hits.getValue("/bttv/cached/emotes/global").get())
    }

    @Test
    fun everythingIsFetchedAgainAfterTheTtl() = runTest {
        cache.directory("10")
        now = 1_500
        cache.directory("10")
        assertEquals(12, requests())
    }

    @Test
    fun aGlobalAnswerWithAProviderDownIsNotRememberedButAMissingChannelSetIs() = runTest {
        failing += "/ffz/set/global"
        val partial = cache.directory("10")
        assertNotNull(partial["GlobalKappa"], "the providers that answered still count")
        val afterFirst = hits.getValue("/bttv/cached/emotes/global").get()

        cache.directory("10")
        assertEquals(afterFirst + 1, hits.getValue("/bttv/cached/emotes/global").get(), "global set is retried")
        assertEquals(1, hits.getValue("/bttv/cached/users/twitch/10").get(), "channel set (7TV 404 = no set) is remembered")
    }

    @Test
    fun onlyTheMostRecentChannelsAreKept() = runTest {
        cache.directory("1")
        cache.directory("2")
        cache.directory("3") // evicts 1
        cache.directory("1")
        assertEquals(2, hits.getValue("/bttv/cached/users/twitch/1").get())
        assertEquals(1, hits.getValue("/bttv/cached/users/twitch/3").get())
    }

    @Test
    fun noChannelMeansGlobalEmotesOnly() = runTest {
        val directory = cache.directory(null)
        assertNotNull(directory["GlobalKappa"])
        assertEquals(3, requests())
    }
}
