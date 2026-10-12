package com.s0undplus.twitch.api

import com.s0undplus.twitch.Category
import com.s0undplus.twitch.Page
import com.s0undplus.twitch.Stream
import com.s0undplus.twitch.TwitchApiException
import com.s0undplus.twitch.TwitchConfig
import com.s0undplus.twitch.TwitchJson
import com.s0undplus.twitch.await
import com.s0undplus.twitch.sizeTemplate
import java.io.IOException
import java.util.Locale
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** How a list of live streams is ordered. [gql] is the name Twitch's directory uses. */
enum class StreamSort(val label: String, val gql: String) {
    VIEWERS("Most viewers", "VIEWER_COUNT"),
    RECENT("Recently started", "RECENT"),
    FEWEST_VIEWERS("Fewest viewers", "VIEWER_COUNT_ASC"),
}

/**
 * What a list of live streams is narrowed to: broadcast [languages] (ISO 639-1 codes, any of them) and the streamers'
 * own [tags] (as Twitch shows them). Empty means no narrowing.
 */
@Serializable
data class StreamFilter(
    val sort: StreamSort = StreamSort.VIEWERS,
    val languages: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
) {
    /** Helix can answer this one too (most viewers first, by language): the fallback when the directory refuses. */
    val helixCan: Boolean get() = sort == StreamSort.VIEWERS && tags.isEmpty()
}

/** A game type Twitch files categories under (Shooter, RPG, IRL…). */
@Serializable
data class CategoryTag(val id: String, val name: String)

/** Twitch said no in a 200 answer (a GraphQL error), or answered with something this client can't read. */
class DirectoryException(message: String) : IOException(message)

/**
 * Twitch's browse directory, with the sorts and filters its website offers and Helix doesn't: newest or least-watched
 * first, several languages at once, the streamers' own tags, and categories by game type.
 *
 * This is Twitch's **unofficial** GraphQL, like playback. It is asked with [TwitchConfig.directoryClientId]: with the
 * web one, Twitch answers the first page but refuses every next one ("failed integrity check"). Callers should fall
 * back to Helix where it can answer the same question ([StreamFilter.helixCan]).
 */
class DirectoryApi(private val http: OkHttpClient, private val config: TwitchConfig) {
    @Volatile private var tagCache: List<CategoryTag>? = null

    /** Live streams, all of Twitch's or one category's ([gameId]), most of them first unless [filter] says otherwise. */
    suspend fun streams(filter: StreamFilter, gameId: String? = null, after: String? = null, first: Int = 30): Page<Stream> {
        val options = buildJsonObject {
            put("sort", filter.sort.gql)
            if (filter.languages.isNotEmpty()) putJsonArray("broadcasterLanguages") { filter.languages.sorted().forEach { add(JsonPrimitive(gqlLanguage(it))) } }
            if (filter.tags.isNotEmpty()) putJsonArray("freeformTags") { filter.tags.sorted().forEach { add(JsonPrimitive(it)) } }
        }
        val data = if (gameId == null) {
            post(STREAMS_QUERY, buildJsonObject {
                put("first", first)
                after?.let { put("after", it) }
                put("options", options)
            })["streams"]
        } else {
            val game = post(GAME_STREAMS_QUERY, buildJsonObject {
                put("id", gameId)
                put("first", first)
                after?.let { put("after", it) }
                put("options", options)
            })["game"]
            // A category Twitch doesn't know (any more) has nobody streaming it.
            (game as? JsonObject)?.get("streams") ?: return Page(emptyList(), null)
        }
        return connection(data, StreamNode.serializer()) { it.toStream() }
    }

    /** Categories by viewers, only those filed under every one of [tagIds] when there are any. */
    suspend fun categories(tagIds: Set<String>, after: String? = null, first: Int = 30): Page<Category> {
        val data = post(GAMES_QUERY, buildJsonObject {
            put("first", first)
            after?.let { put("after", it) }
            putJsonObject("options") {
                if (tagIds.isNotEmpty()) putJsonArray("tags") { tagIds.sorted().forEach { add(JsonPrimitive(it)) } }
            }
        })["games"]
        return connection(data, GameNode.serializer()) { Category(it.id, it.name, sizeTemplate(it.boxArtURL.orEmpty())) }
    }

    /** Every game type, alphabetically. Asked once per run: Twitch's list barely changes. */
    suspend fun categoryTags(): List<CategoryTag> {
        tagCache?.let { return it }
        val list = post(TAGS_QUERY, JsonObject(emptyMap()))["searchCategoryTags"]
        val tags = TwitchJson.decodeFromJsonElement(ListSerializer(TagNode.serializer()), list ?: throw DirectoryException("No category tags"))
            .map { CategoryTag(it.id, it.localizedName) }
            .sortedBy { it.name.lowercase(Locale.ROOT) }
        tagCache = tags
        return tags
    }

    private suspend fun post(query: String, variables: JsonObject): JsonObject {
        val body = buildJsonObject {
            put("query", query)
            put("variables", variables)
        }
        val request = Request.Builder()
            .url(config.gqlUrl)
            .header("Client-ID", config.directoryClientId)
            .header("X-Device-Id", config.deviceId)
            .post(body.toString().toRequestBody(JSON))
            .build()
        http.newCall(request).await().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) throw TwitchApiException(response.code, "Directory request failed", text)
            val root = try {
                TwitchJson.parseToJsonElement(text).jsonObject
            } catch (e: IllegalArgumentException) {
                throw DirectoryException("Unexpected directory answer")
            }
            (root["errors"] as? JsonArray)?.firstOrNull()?.let { e ->
                throw DirectoryException(((e as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull ?: "Directory error")
            }
            return root["data"] as? JsonObject ?: throw DirectoryException("Empty directory answer")
        }
    }

    /** One page of a GraphQL connection; [map] may drop a node by answering null. */
    private fun <N, T : Any> connection(element: JsonElement?, serializer: KSerializer<N>, map: (N) -> T?): Page<T> {
        val obj = element as? JsonObject ?: throw DirectoryException("Unexpected directory answer")
        val edges = (obj["edges"] ?: return Page(emptyList(), null)).jsonArray.mapNotNull { it as? JsonObject }
        val items = edges.mapNotNull { edge -> (edge["node"] as? JsonObject)?.let { TwitchJson.decodeFromJsonElement(serializer, it) } }
        val hasNext = ((obj["pageInfo"] as? JsonObject)?.get("hasNextPage") as? JsonPrimitive)?.contentOrNull == "true"
        val cursor = (edges.lastOrNull()?.get("cursor") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
        return Page(items.mapNotNull(map), if (hasNext) cursor else null)
    }

    @Serializable
    private data class StreamNode(
        val id: String = "",
        val title: String? = null,
        val viewersCount: Int = 0,
        val createdAt: String? = null,
        val type: String? = null,
        val language: String? = null,
        val previewImageURL: String? = null,
        val freeformTags: List<Tag> = emptyList(),
        val broadcaster: User? = null,
        val game: Game? = null,
    ) {
        @Serializable data class Tag(val name: String = "")
        @Serializable data class User(val id: String = "", val login: String = "", val displayName: String? = null)
        @Serializable data class Game(val id: String = "", val name: String = "")

        fun toStream(): Stream? {
            val b = broadcaster ?: return null
            if (b.id.isEmpty() || b.login.isEmpty()) return null
            // Twitch's own ingest checks (colour bars, nobody watching) show up among the newest streams.
            if (b.login.startsWith("ingest_canary")) return null
            return Stream(
                id = id,
                userId = b.id,
                userLogin = b.login,
                userName = b.displayName?.takeIf { it.isNotBlank() } ?: b.login,
                gameId = game?.id.orEmpty(),
                gameName = game?.name.orEmpty(),
                type = type ?: "live",
                title = title.orEmpty(),
                viewerCount = viewersCount,
                startedAt = createdAt.orEmpty(),
                language = language?.let(::isoLanguage).orEmpty(),
                thumbnailUrl = sizeTemplate(previewImageURL.orEmpty()),
                tags = freeformTags.map { it.name }.filter { it.isNotBlank() },
            )
        }
    }

    @Serializable
    private data class GameNode(val id: String, val name: String, val boxArtURL: String? = null)

    @Serializable
    private data class TagNode(val id: String, val localizedName: String)

    companion object {
        private val JSON = "application/json".toMediaType()

        /** Twitch's directory names languages `EN`, `ZH_HK`; Helix and the rest of the app say `en`, `zh-hk`. */
        fun gqlLanguage(iso: String): String = iso.uppercase(Locale.ROOT).replace('-', '_')

        fun isoLanguage(gql: String): String = gql.lowercase(Locale.ROOT).replace('_', '-')

        private const val STREAM_FIELDS = "fragment S on Stream { id title viewersCount createdAt type language " +
            "previewImageURL(width: 440, height: 248) freeformTags { name } broadcaster { id login displayName } game { id name } }"

        private const val STREAMS_QUERY = "query Streams(\$first: Int, \$after: Cursor, \$options: StreamOptions) { " +
            "streams(first: \$first, after: \$after, options: \$options) { edges { cursor node { ...S } } pageInfo { hasNextPage } } } " +
            STREAM_FIELDS

        private const val GAME_STREAMS_QUERY = "query GameStreams(\$id: ID!, \$first: Int, \$after: Cursor, \$options: GameStreamOptions) { " +
            "game(id: \$id) { streams(first: \$first, after: \$after, options: \$options) { edges { cursor node { ...S } } pageInfo { hasNextPage } } } } " +
            STREAM_FIELDS

        private const val GAMES_QUERY = "query Games(\$first: Int, \$after: Cursor, \$options: GameOptions) { " +
            "games(first: \$first, after: \$after, options: \$options) { edges { cursor node { id name boxArtURL(width: 285, height: 380) } } " +
            "pageInfo { hasNextPage } } }"

        private const val TAGS_QUERY = "query CategoryTags { searchCategoryTags(userQuery: \"\", limit: 100) { id localizedName } }"
    }
}
