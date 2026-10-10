package com.s0undplus.twitch.chat

import com.s0undplus.twitch.TwitchConfig
import com.s0undplus.twitch.TwitchJson
import com.s0undplus.twitch.awaitText
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

enum class EmoteProvider { TWITCH, BTTV, FFZ, SEVENTV }

data class Emote(val name: String, val url: String, val provider: EmoteProvider, val animated: Boolean = false)

/** Case-sensitive name → emote lookup (emote codes are case-sensitive on every platform). */
class EmoteDirectory(private val byName: Map<String, Emote>) {
    operator fun get(name: String): Emote? = byName[name]
    val size: Int get() = byName.size

    companion object {
        val EMPTY = EmoteDirectory(emptyMap())

        /**
         * Later entries win, so pass lowest priority first. The app uses: FFZ < BTTV < 7TV, and channel emotes
         * above global ones.
         */
        fun merge(vararg groups: List<Emote>): EmoteDirectory {
            val map = HashMap<String, Emote>()
            for (group in groups) for (e in group) map[e.name] = e
            return EmoteDirectory(map)
        }
    }
}

/** URL for a Twitch-native emote id (static frame, dark-theme variant, 2x: cheap to decode on a TV). */
fun twitchEmoteUrl(id: String): String = "https://static-cdn.jtvnw.net/emoticons/v2/$id/static/dark/2.0"

/**
 * Third-party emote sets (7TV, BetterTTV, FrankerFaceZ). Every provider is best-effort and independent: if one is
 * down or changes its format, chat still works with whatever the others returned.
 */
class ThirdPartyEmotes(private val http: OkHttpClient, private val config: TwitchConfig) {
    /** Loads global + channel emotes for [channelId] (the broadcaster's Twitch user id; null = global only). */
    suspend fun load(channelId: String?): EmoteDirectory = coroutineScope {
        val ffzGlobal = async { fetch("${config.ffzUrl}/set/global", ::parseFfzGlobal) }
        val ffzChannel = async { channelId?.let { fetch("${config.ffzUrl}/room/id/$it", ::parseFfzRoom) } }
        val bttvGlobal = async { fetch("${config.bttvUrl}/cached/emotes/global", ::parseBttvGlobal) }
        val bttvChannel = async { channelId?.let { fetch("${config.bttvUrl}/cached/users/twitch/$it", ::parseBttvChannel) } }
        val sevenGlobal = async { fetch("${config.sevenTvUrl}/emote-sets/global", ::parseSevenTvSet) }
        val sevenChannel = async {
            channelId?.let { fetch("${config.sevenTvUrl}/users/twitch/$it", ::parseSevenTvUser) }
        }
        EmoteDirectory.merge(
            ffzGlobal.await(),
            bttvGlobal.await(),
            sevenGlobal.await(),
            ffzChannel.await().orEmpty(),
            bttvChannel.await().orEmpty(),
            sevenChannel.await().orEmpty(),
        )
    }

    private suspend fun fetch(url: String, parse: (JsonElement) -> List<Emote>): List<Emote> = try {
        val text = http.newCall(Request.Builder().url(url).build()).awaitText()
        parse(TwitchJson.parseToJsonElement(text))
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyList() // network error, 404 (channel has no set) or a format change: skip this provider
    }

    // --- BetterTTV: [{id, code, imageType, animated}] and {channelEmotes:[...], sharedEmotes:[...]} ---

    internal fun parseBttvGlobal(json: JsonElement): List<Emote> = (json as? JsonArray).orEmpty().mapNotNull(::bttvEmote)

    internal fun parseBttvChannel(json: JsonElement): List<Emote> {
        val obj = json as? JsonObject ?: return emptyList()
        return listOf("channelEmotes", "sharedEmotes").flatMap { key ->
            (obj[key] as? JsonArray).orEmpty().mapNotNull(::bttvEmote)
        }
    }

    private fun bttvEmote(e: JsonElement): Emote? {
        val obj = e as? JsonObject ?: return null
        val id = obj.string("id") ?: return null
        val code = obj.string("code") ?: return null
        return Emote(code, "https://cdn.betterttv.net/emote/$id/2x.webp", EmoteProvider.BTTV, obj.bool("animated"))
    }

    // --- 7TV: {emotes:[{name, data:{animated, host:{url, files:[{name}]}}}]} and {emote_set:{emotes:[...]}} ---

    internal fun parseSevenTvSet(json: JsonElement): List<Emote> =
        ((json as? JsonObject)?.get("emotes") as? JsonArray).orEmpty().mapNotNull(::sevenTvEmote)

    internal fun parseSevenTvUser(json: JsonElement): List<Emote> =
        parseSevenTvSet((json as? JsonObject)?.get("emote_set") ?: return emptyList())

    private fun sevenTvEmote(e: JsonElement): Emote? {
        val obj = e as? JsonObject ?: return null
        val name = obj.string("name") ?: return null
        val data = obj["data"] as? JsonObject ?: return null
        val host = data["host"] as? JsonObject ?: return null
        val base = host.string("url") ?: return null
        val files = (host["files"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.string("name") }
        val file = files.firstOrNull { it == "2x.webp" }
            ?: files.firstOrNull { it.startsWith("2x") }
            ?: files.firstOrNull()
            ?: "2x.webp"
        return Emote(name, "${absolute(base)}/$file", EmoteProvider.SEVENTV, data.bool("animated"))
    }

    // --- FFZ: {sets:{"<id>":{emoticons:[{name, urls:{"1":"//cdn..","2":"..."}}]}}, default_sets:[...]} ---

    internal fun parseFfzGlobal(json: JsonElement): List<Emote> {
        val obj = json as? JsonObject ?: return emptyList()
        val sets = obj["sets"] as? JsonObject ?: return emptyList()
        val defaults = (obj["default_sets"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }
        return defaults.flatMap { id -> ffzSet(sets[id]) }
    }

    internal fun parseFfzRoom(json: JsonElement): List<Emote> {
        val sets = (json as? JsonObject)?.get("sets") as? JsonObject ?: return emptyList()
        return sets.values.flatMap(::ffzSet)
    }

    private fun ffzSet(set: JsonElement?): List<Emote> =
        ((set as? JsonObject)?.get("emoticons") as? JsonArray).orEmpty().mapNotNull { e ->
            val obj = e as? JsonObject ?: return@mapNotNull null
            val name = obj.string("name") ?: return@mapNotNull null
            val urls = obj["urls"] as? JsonObject ?: return@mapNotNull null
            val url = urls.string("2") ?: urls.string("1") ?: urls.values.firstNotNullOfOrNull {
                it.jsonPrimitive.contentOrNull
            } ?: return@mapNotNull null
            Emote(name, absolute(url), EmoteProvider.FFZ)
        }

    private fun absolute(url: String): String = if (url.startsWith("//")) "https:$url" else url

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.bool(key: String): Boolean =
        this[key]?.jsonPrimitive?.let { it.contentOrNull == "true" || it.intOrNull == 1 } ?: false

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}
