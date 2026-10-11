package com.s0undplus.twitch.chat

import com.s0undplus.twitch.TwitchApiException
import com.s0undplus.twitch.TwitchConfig
import com.s0undplus.twitch.TwitchJson
import com.s0undplus.twitch.await
import java.io.IOException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Twitch answered the chat request with an error (e.g. "failed integrity check") rather than failing to answer. */
class VodChatRefusedException(message: String) : IOException(message)

data class VodChatPage(val messages: List<ChatMessage>, val nextCursor: String?, val hasNext: Boolean)

/**
 * Chat history of a past broadcast, from Twitch's undocumented `VideoCommentsByOffsetOrCursor` GraphQL operation (the
 * same one the website uses). Unofficial like playback: callers must treat failure as "replay unavailable" and never
 * let it affect video playback.
 */
class VodChatApi(private val http: OkHttpClient, private val config: TwitchConfig) {
    /** Fetch the first page at [offsetSeconds], or continue from [cursor] (exactly one should be given). */
    suspend fun page(videoId: String, offsetSeconds: Int? = null, cursor: String? = null): VodChatPage {
        val body = buildJsonObject {
            put("operationName", "VideoCommentsByOffsetOrCursor")
            putJsonObject("variables") {
                put("videoID", videoId)
                if (cursor != null) put("cursor", cursor) else put("contentOffsetSeconds", offsetSeconds ?: 0)
            }
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("version", 1)
                    put("sha256Hash", PERSISTED_HASH)
                }
            }
        }
        val request = Request.Builder()
            .url(config.gqlUrl)
            .header("Client-ID", config.commentsClientId)
            .header("X-Device-Id", config.deviceId)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).await().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) throw TwitchApiException(response.code, "VOD chat request failed", text)
            return parse(text)
        }
    }

    internal fun parse(text: String): VodChatPage {
        val root = TwitchJson.parseToJsonElement(text)
        val obj = (root as? JsonArray)?.firstOrNull() as? JsonObject ?: root as? JsonObject
            ?: throw IOException("Unexpected VOD chat response")
        (obj["errors"] as? JsonArray)?.firstOrNull()?.let { e ->
            throw VodChatRefusedException(((e as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull ?: "VOD chat error")
        }
        val comments = (((obj["data"] as? JsonObject)?.get("video") as? JsonObject)?.get("comments")) as? JsonObject
            ?: return VodChatPage(emptyList(), null, false)
        val edges = (comments["edges"] as? JsonArray).orEmpty()
        val messages = edges.mapNotNull { edge -> ((edge as? JsonObject)?.get("node") as? JsonObject)?.let(::message) }
        val lastCursor = (edges.lastOrNull() as? JsonObject)?.str("cursor")
        val hasNext = ((comments["pageInfo"] as? JsonObject)?.get("hasNextPage") as? JsonPrimitive)?.contentOrNull == "true"
        return VodChatPage(messages, lastCursor, hasNext && lastCursor != null)
    }

    private fun message(node: JsonObject): ChatMessage? {
        val id = node.str("id") ?: return null
        val offsetMs = ((node["contentOffsetSeconds"] as? JsonPrimitive)?.doubleOrNull ?: return null) * 1000
        val commenter = node["commenter"] as? JsonObject
        val login = commenter?.str("login").orEmpty()
        val msg = node["message"] as? JsonObject ?: return null

        val text = StringBuilder()
        val emotes = mutableListOf<NativeEmote>()
        var codePoints = 0
        for (fragment in (msg["fragments"] as? JsonArray).orEmpty()) {
            val f = fragment as? JsonObject ?: continue
            val piece = f.str("text").orEmpty()
            val length = piece.codePointCount(0, piece.length)
            val emoteId = (f["emote"] as? JsonObject)?.str("emoteID")
            if (emoteId != null && length > 0) emotes += NativeEmote(emoteId, codePoints, codePoints + length - 1)
            text.append(piece)
            codePoints += length
        }
        val badges = (msg["userBadges"] as? JsonArray).orEmpty().mapNotNull {
            val b = it as? JsonObject ?: return@mapNotNull null
            BadgeRef(b.str("setID") ?: return@mapNotNull null, b.str("version") ?: return@mapNotNull null)
        }
        return ChatMessage(
            id = id,
            roomId = "",
            userId = commenter?.str("id").orEmpty(),
            login = login,
            displayName = commenter?.str("displayName")?.takeIf { it.isNotBlank() } ?: login.ifEmpty { "Deleted user" },
            colorHex = msg.str("userColor")?.takeIf { it.isNotBlank() },
            badges = badges,
            text = text.toString(),
            isAction = false,
            nativeEmotes = emotes,
            timestampMillis = 0L,
            vodOffsetMs = offsetMs.toLong(),
        )
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

    private companion object {
        const val PERSISTED_HASH = "b70a3591ff0f4e0313d126c6a1502d79a1c02baebb288227c582044aa76adf6a"
    }
}

/**
 * Turns a paged chat history into "what should be on screen now" as the video plays. Fetches ahead of the playhead,
 * releases messages as the position passes their timestamp, and starts over after a seek.
 */
class VodChatReplay(
    private val fetch: suspend (offsetSeconds: Int?, cursor: String?) -> VodChatPage,
    private val lookaheadMs: Long = 30_000,
    private val backfillMs: Long = 15_000,
    private val maxBackfillMessages: Int = 25,
) {
    private val buffer = ArrayDeque<ChatMessage>()
    private var cursor: String? = null
    private var hasMore = true
    private var primed = false

    /**
     * Set once a cursor request has failed. Twitch has rejected cursor continuations ("failed integrity check") for
     * some client IDs while still answering requests by time offset, so from then on paging continues by offset.
     */
    private var byOffset = false

    /** Offset of the newest message taken in, and the ids taken in at exactly that offset (offset pages overlap). */
    private var newestOffsetMs = -1L
    private val idsAtNewest = HashSet<String>()

    /** Where the next offset request starts; nudged forward when a page added nothing new. */
    private var nextOffsetSeconds = 0

    /** Messages that became due since the last call, oldest first. Throws if the chat service fails. */
    suspend fun advance(positionMs: Long): List<ChatMessage> {
        var justPrimed = false
        if (!primed) {
            val startSeconds = ((positionMs - backfillMs).coerceAtLeast(0) / 1000).toInt()
            accept(fetch(startSeconds, null), requestedSeconds = startSeconds)
            primed = true
            justPrimed = true
        }
        var pages = 0
        while (hasMore && pages < MAX_PAGES_PER_CALL && (buffer.isEmpty() || (buffer.last().vodOffsetMs ?: 0) < positionMs + lookaheadMs)) {
            nextPage()
            pages++
        }
        val due = ArrayList<ChatMessage>()
        while (buffer.isNotEmpty() && (buffer.first().vodOffsetMs ?: 0) <= positionMs) due += buffer.removeFirst()
        return if (justPrimed) due.takeLast(maxBackfillMessages) else due
    }

    /** Call when the playhead jumped (user seek): the buffer no longer matches the position. */
    fun seek() {
        buffer.clear()
        cursor = null
        hasMore = true
        primed = false
        newestOffsetMs = -1L
        idsAtNewest.clear()
        // [byOffset] is kept: a cursor that was refused once will be refused again.
    }

    private suspend fun nextPage() {
        val current = cursor
        if (!byOffset && current != null) {
            try {
                accept(fetch(null, current), requestedSeconds = null)
                return
            } catch (_: VodChatRefusedException) {
                // Twitch answered but refused the cursor: continue by time instead, from now on. Network failures are
                // not caught here; they reach the caller, which retries with the cursor intact.
                byOffset = true
            }
        }
        val from = nextOffsetSeconds
        accept(fetch(from, null), requestedSeconds = from)
    }

    private fun accept(page: VodChatPage, requestedSeconds: Int?) {
        // Offset pages start at a whole second and so repeat what was already taken in at that second: skip those.
        val fresh = page.messages.filter { message ->
            val offset = message.vodOffsetMs ?: 0
            offset > newestOffsetMs || (offset == newestOffsetMs && message.id !in idsAtNewest)
        }
        for (message in fresh) {
            val offset = message.vodOffsetMs ?: 0
            if (offset > newestOffsetMs) {
                newestOffsetMs = offset
                idsAtNewest.clear()
            }
            idsAtNewest += message.id
        }
        buffer.addAll(fresh)
        val newestSecond = (newestOffsetMs.coerceAtLeast(0) / 1000).toInt()
        if (byOffset) {
            // Continuing by time: an empty page means the end of the video. A page that added nothing new (a single
            // second with more messages than a page holds) moves on a second, so this always makes progress.
            hasMore = page.messages.isNotEmpty()
            nextOffsetSeconds = if (fresh.isEmpty()) (requestedSeconds ?: nextOffsetSeconds) + 1 else newestSecond
        } else {
            hasMore = page.hasNext && page.nextCursor != null && page.nextCursor != cursor
            nextOffsetSeconds = newestSecond
        }
        cursor = page.nextCursor
    }

    private companion object {
        const val MAX_PAGES_PER_CALL = 4
    }
}
