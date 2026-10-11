package com.s0undplus.twitch.chat

import com.s0undplus.twitch.configFor
import com.s0undplus.twitch.json
import com.s0undplus.twitch.testHttp
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockWebServer

class VodChatTest {
    private lateinit var server: MockWebServer

    @BeforeTest
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @AfterTest
    fun tearDown() = server.close()

    // Shape of a real VideoCommentsByOffsetOrCursor answer (the website sends/receives a one-element array).
    private val response = """
        [{"data":{"video":{"id":"123","comments":{"edges":[
          {"cursor":"C1","node":{"id":"m1","contentOffsetSeconds":12.5,"createdAt":"2026-01-01T00:00:12Z",
            "commenter":{"id":"5","login":"alice","displayName":"Alice"},
            "message":{"userColor":"#FF0000","userBadges":[{"id":"x","setID":"subscriber","version":"6"}],
              "fragments":[{"text":"hi ","emote":null},{"text":"Kappa","emote":{"emoteID":"25"}},{"text":" 😀 PogChamp","emote":null}]}}},
          {"cursor":"C2","node":{"id":"m2","contentOffsetSeconds":14,"commenter":null,
            "message":{"userColor":null,"userBadges":[],"fragments":[{"text":"deleted user msg","emote":null}]}}},
          {"cursor":"C3","node":{"missing":"fields"}}
        ],"pageInfo":{"hasNextPage":true,"hasPreviousPage":false}}}},"extensions":{"durationMilliseconds":12}}]
    """.trimIndent()

    @Test
    fun parsesMessagesWithFragmentsBadgesAndOffsets() {
        val page = VodChatApi(testHttp(), configFor(server)).parse(response)
        assertEquals(2, page.messages.size) // the malformed node is skipped
        assertEquals("C3", page.nextCursor)
        assertTrue(page.hasNext)

        val first = page.messages[0]
        assertEquals("alice", first.login)
        assertEquals("Alice", first.displayName)
        assertEquals(12_500L, first.vodOffsetMs)
        assertEquals("#FF0000", first.colorHex)
        assertEquals(listOf(BadgeRef("subscriber", "6")), first.badges)
        assertEquals("hi Kappa 😀 PogChamp", first.text)
        // "Kappa" starts at code point 3 (after "hi ") and spans 5 code points.
        assertEquals(listOf(NativeEmote("25", 3, 7)), first.nativeEmotes)

        val second = page.messages[1]
        assertEquals("Deleted user", second.displayName)
        assertEquals(null, second.colorHex)
    }

    @Test
    fun emptyOrMissingCommentsMeanNoMessages() {
        val api = VodChatApi(testHttp(), configFor(server))
        assertEquals(0, api.parse("""[{"data":{"video":{"comments":null}}}]""").messages.size)
        assertEquals(0, api.parse("""{"data":{"video":null}}""").messages.size)
        assertFailsWith<IOException> { api.parse("""[{"errors":[{"message":"service error"}]}]""") }
    }

    @Test
    fun requestUsesPersistedQueryAndOffsetThenCursor() = runBlocking {
        server.enqueue(json(response))
        server.enqueue(json(response))
        val api = VodChatApi(testHttp(), configFor(server))

        api.page("123", offsetSeconds = 90)
        val first = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals("VideoCommentsByOffsetOrCursor", first["operationName"]?.jsonPrimitive?.content)
        val vars = first["variables"]!!.jsonObject
        assertEquals("123", vars["videoID"]?.jsonPrimitive?.content)
        assertEquals("90", vars["contentOffsetSeconds"]?.jsonPrimitive?.content)
        assertTrue(!vars.containsKey("cursor"))
        assertTrue(first["extensions"]!!.jsonObject["persistedQuery"]!!.jsonObject.containsKey("sha256Hash"))

        api.page("123", cursor = "C3")
        val second = server.takeRequest()
        assertEquals("device-1", second.headers["X-Device-Id"])
        // Not the web client ID, whose cursor requests Twitch refuses with "failed integrity check".
        assertEquals("kd1unb4b3q4t58fwlpcbzcbnm76a8fp", second.headers["Client-ID"])
        val vars2 = Json.parseToJsonElement(second.body!!.utf8()).jsonObject["variables"]!!.jsonObject
        assertEquals("C3", vars2["cursor"]?.jsonPrimitive?.content)
        assertTrue(!vars2.containsKey("contentOffsetSeconds"))
    }

    // --- replay ---

    private fun msg(id: String, offsetMs: Long) = ChatMessage(
        id = id, roomId = "", userId = "", login = "u", displayName = "U", colorHex = null, badges = emptyList(),
        text = id, isAction = false, nativeEmotes = emptyList(), timestampMillis = 0, vodOffsetMs = offsetMs,
    )

    /** A fake chat service: pages of messages every 10 s of video, 3 per page. */
    private class FakeService(val all: List<ChatMessage>) {
        val calls = mutableListOf<Pair<Int?, String?>>()

        suspend fun fetch(offsetSeconds: Int?, cursor: String?): VodChatPage {
            calls += offsetSeconds to cursor
            val startIndex = when {
                cursor != null -> cursor.removePrefix("i").toInt()
                else -> all.indexOfFirst { (it.vodOffsetMs ?: 0) >= (offsetSeconds ?: 0) * 1000L }.let { if (it < 0) all.size else it }
            }
            val slice = all.drop(startIndex).take(3)
            val end = startIndex + slice.size
            return VodChatPage(slice, if (end < all.size) "i$end" else null, end < all.size)
        }
    }

    @Test
    fun replayReleasesMessagesAsThePlayheadPassesThem() = runBlocking {
        val service = FakeService((1..12).map { msg("m$it", it * 5_000L) }) // m1@5s ... m12@60s
        val replay = VodChatReplay(service::fetch, lookaheadMs = 20_000, backfillMs = 0)

        assertEquals(emptyList(), replay.advance(1_000).map { it.id })
        assertEquals(listOf("m1", "m2"), replay.advance(10_000).map { it.id })
        assertEquals(emptyList(), replay.advance(10_500).map { it.id }) // nothing new, nothing re-sent
        assertEquals(listOf("m3", "m4", "m5"), replay.advance(25_000).map { it.id })
    }

    @Test
    fun replayFetchesAheadButNotFarAhead() = runBlocking {
        val service = FakeService((1..30).map { msg("m$it", it * 5_000L) })
        val replay = VodChatReplay(service::fetch, lookaheadMs = 20_000, backfillMs = 0)
        replay.advance(0)
        // Needed up to position+20s = 20s of buffer: 4 messages = 2 pages (first by offset, then by cursor).
        assertTrue(service.calls.size in 2..3, service.calls.toString())
        assertTrue(service.calls.size < 10)
    }

    @Test
    fun seekDropsTheBufferAndRestartsFromTheNewPosition() = runBlocking {
        val service = FakeService((1..30).map { msg("m$it", it * 5_000L) })
        val replay = VodChatReplay(service::fetch, lookaheadMs = 10_000, backfillMs = 10_000, maxBackfillMessages = 25)
        replay.advance(20_000)
        replay.seek()
        val after = replay.advance(100_000).map { it.id }
        // Restarted at offset 90 s (position minus 10 s backfill): context messages up to the playhead, none from before.
        assertEquals(listOf("m18", "m19", "m20"), after)
        assertEquals(90, service.calls.last { it.second == null }.first)
    }

    @Test
    fun backfillIsCappedAndCatchUpDrainsInOrderWithoutDuplicates() = runBlocking {
        val service = FakeService((1..50).map { msg("m$it", it * 100L) }) // 50 messages within the first 5 s
        val replay = VodChatReplay(service::fetch, lookaheadMs = 0, backfillMs = 60_000, maxBackfillMessages = 5)

        // One call fetches at most 4 pages (12 more messages after the first page), and shows only the last 5 of those.
        val first = replay.advance(10_000)
        assertEquals(listOf("m11", "m12", "m13", "m14", "m15"), first.map { it.id })

        // The remaining due messages are released on the next ticks, in order, never repeated.
        val rest = generateSequence { runBlocking { replay.advance(10_000) } }.take(10).flatten().map { it.id }.toList()
        assertEquals((16..50).map { "m$it" }, rest)
    }

    @Test
    fun aStuckCursorCannotLoopForever() = runBlocking {
        var calls = 0
        val replay = VodChatReplay(
            fetch = { _, _ ->
                calls++
                VodChatPage(emptyList(), "SAME", hasNext = true) // server keeps saying "there is more" with no data
            },
            lookaheadMs = 30_000,
        )
        replay.advance(5_000)
        assertTrue(calls <= 6, "made $calls calls")
    }

    /** Twitch answering offset requests but refusing every cursor, as it did for the web client ID on 2026-10-11. */
    private class CursorRefusingService(all: List<ChatMessage>) {
        val inner = FakeService(all)
        var cursorCalls = 0

        suspend fun fetch(offsetSeconds: Int?, cursor: String?): VodChatPage {
            if (cursor != null) {
                cursorCalls++
                throw IOException("failed integrity check")
            }
            return inner.fetch(offsetSeconds, null)
        }
    }

    @Test
    fun whenCursorsAreRefusedReplayCarriesOnByTimeWithoutGapsOrRepeats() = runBlocking {
        val service = CursorRefusingService((1..40).map { msg("m$it", it * 2_500L) }) // m1@2.5s ... m40@100s
        val replay = VodChatReplay(service::fetch, lookaheadMs = 10_000, backfillMs = 0)
        val seen = ArrayList<String>()
        var position = 0L
        while (position <= 110_000) {
            seen += replay.advance(position).map { it.id }
            position += 1_000
        }
        assertEquals((1..40).map { "m$it" }, seen)
        assertEquals(1, service.cursorCalls) // tried once, then never again
    }

    @Test
    fun timePagingMovesPastASecondThatHoldsMoreThanAPage() = runBlocking {
        // Seven messages in the same second (pages hold three), then one more later.
        val burst = (1..7).map { msg("b$it", 5_000L) } + msg("after", 9_000L)
        val service = CursorRefusingService(burst)
        val replay = VodChatReplay(service::fetch, lookaheadMs = 10_000, backfillMs = 0)
        val seen = ArrayList<String>()
        for (position in 0L..20_000L step 1_000L) seen += replay.advance(position).map { it.id }
        // The paging can't see beyond a page within one second, so some of the burst may be skipped, but it must not
        // stall there and must never repeat a message.
        assertTrue("after" in seen, seen.toString())
        assertEquals(seen.distinct(), seen)
    }

    @Test
    fun serviceFailuresSurfaceToTheCaller() = runBlocking {
        val replay = VodChatReplay({ _, _ -> throw IOException("down") })
        assertFailsWith<IOException> { replay.advance(0) }
    }
}
