package com.s0undplus.twitch.chat

import com.s0undplus.twitch.TwitchJson
import com.s0undplus.twitch.configFor
import com.s0undplus.twitch.json
import com.s0undplus.twitch.testHttp
import com.s0undplus.twitch.text
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest

class EmotesTest {
    private lateinit var server: MockWebServer
    private lateinit var emotes: ThirdPartyEmotes

    @BeforeTest
    fun setUp() {
        server = MockWebServer().also { it.start() }
        emotes = ThirdPartyEmotes(testHttp(), configFor(server))
    }

    @AfterTest
    fun tearDown() = server.close()

    private val bttvGlobal = """[{"id":"54fa925e01e468494b85b54d","code":"OMGScoots","imageType":"png","animated":false},
        {"id":"5f1b0186cf6d2144653d2970","code":"cvHazmat","imageType":"gif","animated":true}]"""
    private val bttvChannel = """{"id":"x","bots":[],"channelEmotes":[{"id":"c1","code":"Chan","imageType":"png","animated":false}],
        "sharedEmotes":[{"id":"s1","code":"Shared","imageType":"webp","animated":true,"user":{"id":"u"}}]}"""
    private val sevenSet = """{"id":"g","name":"Global","emotes":[{"id":"e1","name":"EZ","flags":0,
        "data":{"id":"e1","name":"EZ","animated":true,"host":{"url":"//cdn.7tv.app/emote/e1","files":[
        {"name":"1x.webp","format":"WEBP"},{"name":"2x.webp","format":"WEBP"},{"name":"4x.webp","format":"WEBP"}]}}}]}"""
    private val sevenUser = """{"id":"u","platform":"TWITCH","emote_set":{"id":"s","emotes":[{"id":"e2","name":"Mine",
        "data":{"animated":false,"host":{"url":"//cdn.7tv.app/emote/e2","files":[{"name":"1x.avif"},{"name":"2x.avif"}]}}}]}}"""
    private val ffzGlobal = """{"default_sets":[3],"sets":{"3":{"emoticons":[{"id":1,"name":"ZrehplaR","urls":{"1":"//cdn.frankerfacez.com/emote/1/1","2":"//cdn.frankerfacez.com/emote/1/2","4":"//cdn.frankerfacez.com/emote/1/4"}}]},"9":{"emoticons":[{"id":2,"name":"NotDefault","urls":{"1":"//x/1"}}]}}}"""
    private val ffzRoom = """{"room":{"set":55},"sets":{"55":{"emoticons":[{"id":7,"name":"RoomEmote","urls":{"1":"https://cdn.frankerfacez.com/emote/7/1"}}]}}}"""

    private fun parse(s: String) = TwitchJson.parseToJsonElement(s)

    @Test
    fun parsesBttv() {
        val global = emotes.parseBttvGlobal(parse(bttvGlobal))
        assertEquals(listOf("OMGScoots", "cvHazmat"), global.map { it.name })
        assertEquals("https://cdn.betterttv.net/emote/54fa925e01e468494b85b54d/2x.webp", global[0].url)
        assertEquals(true, global[1].animated)

        val channel = emotes.parseBttvChannel(parse(bttvChannel))
        assertEquals(listOf("Chan", "Shared"), channel.map { it.name })
    }

    @Test
    fun parsesSevenTvAndPrefersTwoXFiles() {
        val global = emotes.parseSevenTvSet(parse(sevenSet)).single()
        assertEquals("EZ", global.name)
        assertEquals("https://cdn.7tv.app/emote/e1/2x.webp", global.url)
        assertEquals(true, global.animated)

        val user = emotes.parseSevenTvUser(parse(sevenUser)).single()
        assertEquals("https://cdn.7tv.app/emote/e2/2x.avif", user.url) // first "2x*" file when no 2x.webp
    }

    @Test
    fun parsesFfzUsingOnlyDefaultSetsForGlobal() {
        val global = emotes.parseFfzGlobal(parse(ffzGlobal))
        assertEquals(listOf("ZrehplaR"), global.map { it.name })
        assertEquals("https://cdn.frankerfacez.com/emote/1/2", global[0].url)

        val room = emotes.parseFfzRoom(parse(ffzRoom)).single()
        assertEquals("https://cdn.frankerfacez.com/emote/7/1", room.url)
    }

    @Test
    fun malformedPayloadsYieldNothingInsteadOfThrowing() {
        for (garbage in listOf("{}", "[]", "[1,2]", """{"emotes":"nope"}""", "null")) {
            emotes.parseBttvGlobal(parse(garbage))
            emotes.parseBttvChannel(parse(garbage))
            emotes.parseSevenTvSet(parse(garbage))
            emotes.parseSevenTvUser(parse(garbage))
            emotes.parseFfzGlobal(parse(garbage))
            emotes.parseFfzRoom(parse(garbage))
        }
    }

    @Test
    fun loadMergesProvidersWithSevenTvWinningAndSurvivesAFailingProvider() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/bttv/cached/emotes/global" ->
                    json("""[{"id":"b1","code":"Clash","animated":false},{"id":"b2","code":"OnlyBttv","animated":false}]""")
                "/7tv/emote-sets/global" ->
                    json("""{"emotes":[{"name":"Clash","data":{"animated":false,"host":{"url":"//cdn.7tv.app/emote/s1","files":[{"name":"2x.webp"}]}}}]}""")
                "/bttv/cached/users/twitch/42" -> json("""{"channelEmotes":[{"id":"c","code":"ChanB"}],"sharedEmotes":[]}""")
                "/7tv/users/twitch/42" -> text("not found", 404) // channel has no 7TV set
                "/ffz/set/global" -> text("boom", 500)
                "/ffz/room/id/42" -> json(ffzRoom)
                else -> text("", 404)
            }
        }

        val directory = emotes.load("42")

        assertEquals(EmoteProvider.SEVENTV, directory["Clash"]?.provider)
        assertEquals(EmoteProvider.BTTV, directory["OnlyBttv"]?.provider)
        assertEquals(EmoteProvider.BTTV, directory["ChanB"]?.provider)
        assertNotNull(directory["RoomEmote"])
        assertTrue(directory["Missing"] == null)
    }
}
