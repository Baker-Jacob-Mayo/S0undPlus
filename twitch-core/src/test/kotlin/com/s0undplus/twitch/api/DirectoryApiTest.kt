package com.s0undplus.twitch.api

import com.s0undplus.twitch.Category
import com.s0undplus.twitch.configFor
import com.s0undplus.twitch.json
import com.s0undplus.twitch.testHttp
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest

class DirectoryApiTest {
    private lateinit var server: MockWebServer
    private lateinit var directory: DirectoryApi

    @BeforeTest
    fun setUp() {
        server = MockWebServer().also { it.start() }
        directory = DirectoryApi(testHttp(), configFor(server))
    }

    @AfterTest
    fun tearDown() = server.close()

    private fun RecordedRequest.variables(): JsonObject =
        Json.parseToJsonElement(body!!.utf8()).jsonObject["variables"]!!.jsonObject

    private val streamsPage = """
        {"data":{"streams":{"edges":[
          {"cursor":"C1","node":{"id":"s1","title":"hello","viewersCount":512,"createdAt":"2026-10-12T07:18:29Z","type":"live",
           "language":"ZH_HK","previewImageURL":"https://static-cdn.jtvnw.net/previews-ttv/live_user_alice-440x248.jpg",
           "freeformTags":[{"name":"English"},{"name":""}],"broadcaster":{"id":"10","login":"alice","displayName":"Alice"},
           "game":{"id":"32399","name":"Counter-Strike"}}},
          {"cursor":"C2","node":{"id":"s2","viewersCount":3,"broadcaster":null}},
          {"cursor":"C3","node":{"id":"s3","broadcaster":{"id":"9","login":"ingest_canary_euw13_3"}}}
        ],"pageInfo":{"hasNextPage":true}}}}
    """.trimIndent()

    @Test
    fun streamsSendTheSortLanguagesAndTagsAndMapIntoStreams() = runTest {
        server.enqueue(json(streamsPage))
        val page = directory.streams(StreamFilter(StreamSort.RECENT, setOf("zh-hk", "en"), setOf("English")), after = "C0", first = 24)

        val request = server.takeRequest()
        assertEquals("kd1unb4b3q4t58fwlpcbzcbnm76a8fp", request.headers["Client-ID"])
        val vars = request.variables()
        assertEquals("C0", vars["after"]!!.jsonPrimitive.content)
        assertEquals("24", vars["first"]!!.jsonPrimitive.content)
        val options = vars["options"]!!.jsonObject
        assertEquals("RECENT", options["sort"]!!.jsonPrimitive.content)
        assertEquals(listOf("EN", "ZH_HK"), options["broadcasterLanguages"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("English"), options["freeformTags"]!!.jsonArray.map { it.jsonPrimitive.content })

        // A node without a broadcaster, or one of Twitch's ingest checks, is dropped; the rest maps onto Helix's shape.
        val s = page.items.single()
        assertEquals("10", s.userId)
        assertEquals("alice", s.userLogin)
        assertEquals("Alice", s.userName)
        assertEquals("zh-hk", s.language)
        assertEquals("Counter-Strike", s.gameName)
        assertEquals(512, s.viewerCount)
        assertEquals(listOf("English"), s.tags)
        assertEquals("https://static-cdn.jtvnw.net/previews-ttv/live_user_alice-320x180.jpg", s.thumbnail(320, 180))
        assertEquals("C3", page.nextCursor)
    }

    @Test
    fun anUnfilteredListSendsNoLanguagesOrTagsAndTheLastPageHasNoCursor() = runTest {
        server.enqueue(json("""{"data":{"streams":{"edges":[{"cursor":"C9","node":{"id":"s","broadcaster":{"id":"1","login":"bob"}}}],"pageInfo":{"hasNextPage":false}}}}"""))
        val page = directory.streams(StreamFilter())

        val options = server.takeRequest().variables()["options"]!!.jsonObject
        assertEquals("VIEWER_COUNT", options["sort"]!!.jsonPrimitive.content)
        assertTrue("broadcasterLanguages" !in options && "freeformTags" !in options)
        assertEquals("bob", page.items.single().userName)
        assertNull(page.nextCursor)
    }

    @Test
    fun aCategorysStreamsAskForThatGameAndAnUnknownGameIsEmpty() = runTest {
        server.enqueue(json("""{"data":{"game":null}}"""))
        val page = directory.streams(StreamFilter(), gameId = "32399")

        assertEquals("32399", server.takeRequest().variables()["id"]!!.jsonPrimitive.content)
        assertTrue(page.items.isEmpty())
        assertNull(page.nextCursor)
    }

    @Test
    fun aGraphQlErrorIsAFailureNotAnEmptyList() = runTest {
        server.enqueue(json("""{"errors":[{"message":"failed integrity check"}],"data":{"streams":null}}"""))
        val e = assertFailsWith<DirectoryException> { directory.streams(StreamFilter()) }
        assertEquals("failed integrity check", e.message)
    }

    @Test
    fun categoriesFilterByEveryTagAndKeepABoxArtTemplate() = runTest {
        server.enqueue(json("""
            {"data":{"games":{"edges":[{"cursor":"G1","node":{"id":"32399","name":"Counter-Strike",
            "boxArtURL":"https://static-cdn.jtvnw.net/ttv-boxart/32399-285x380.jpg"}}],"pageInfo":{"hasNextPage":false}}}}
        """.trimIndent()))
        val page = directory.categories(setOf("t2", "t1"))

        val tags = server.takeRequest().variables()["options"]!!.jsonObject["tags"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("t1", "t2"), tags)
        assertEquals(listOf(Category("32399", "Counter-Strike", "https://static-cdn.jtvnw.net/ttv-boxart/32399-{width}x{height}.jpg")), page.items)
    }

    @Test
    fun categoryTagsComeAlphabeticallyAndAreAskedForOnce() = runTest {
        server.enqueue(json("""{"data":{"searchCategoryTags":[{"id":"b","localizedName":"Shooter"},{"id":"a","localizedName":"action"}]}}"""))
        val first = directory.categoryTags()
        val second = directory.categoryTags()

        assertEquals(listOf(CategoryTag("a", "action"), CategoryTag("b", "Shooter")), first)
        assertEquals(first, second)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun languagesTranslateBetweenTheDirectoryAndIsoCodes() {
        assertEquals("ZH_HK", DirectoryApi.gqlLanguage("zh-hk"))
        assertEquals("en", DirectoryApi.isoLanguage("EN"))
        assertTrue(StreamFilter(languages = setOf("en", "da")).helixCan)
        assertTrue(!StreamFilter(sort = StreamSort.RECENT).helixCan && !StreamFilter(tags = setOf("x")).helixCan)
    }
}
