package com.s0undplus.twitch.playback

import com.s0undplus.twitch.TwitchApiException
import com.s0undplus.twitch.TwitchConfig
import com.s0undplus.twitch.TwitchJson
import com.s0undplus.twitch.await
import kotlin.math.floor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Where one seek-preview picture is: a tile of [imageUrl], a sprite sheet of many small frames. */
data class SeekPreviewFrame(val imageUrl: String, val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * A past broadcast's seek previews ("storyboards"): sprite sheets of [cols] × [rows] frames of [width] × [height]
 * pixels, one frame every [intervalSeconds], [count] frames in all, spread over [images] in order.
 */
data class SeekPreviews(
    val count: Int,
    val width: Int,
    val height: Int,
    val rows: Int,
    val cols: Int,
    val intervalSeconds: Double,
    val images: List<String>,
) {
    /** The frame closest before [seconds] into the video, or null if there is none. */
    fun frameAt(seconds: Double): SeekPreviewFrame? {
        if (count <= 0 || rows <= 0 || cols <= 0 || intervalSeconds <= 0 || images.isEmpty()) return null
        val index = floor(seconds.coerceAtLeast(0.0) / intervalSeconds).toInt().coerceIn(0, count - 1)
        val perSheet = rows * cols
        val sheet = index / perSheet
        if (sheet >= images.size) return null
        val tile = index % perSheet
        return SeekPreviewFrame(images[sheet], (tile % cols) * width, (tile / cols) * height, width, height)
    }

    companion object {
        /**
         * Parses Twitch's storyboard description, a JSON array with one entry per quality, taking the sharpest one.
         * Image names in it are relative to [infoUrl].
         */
        fun parse(json: String, infoUrl: String): SeekPreviews? {
            val base = infoUrl.toHttpUrlOrNull() ?: return null
            val entries = (TwitchJson.parseToJsonElement(json) as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return null
            val best = entries.firstOrNull { it.str("quality") == "high" } ?: entries.maxByOrNull { it.int("width") ?: 0 } ?: return null
            val images = (best["images"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?.mapNotNull { name -> base.resolve(name)?.toString() }
                .orEmpty()
            return SeekPreviews(
                count = best.int("count") ?: return null,
                width = best.int("width") ?: return null,
                height = best.int("height") ?: return null,
                rows = best.int("rows") ?: return null,
                cols = best.int("cols") ?: return null,
                intervalSeconds = (best["interval"] as? JsonPrimitive)?.doubleOrNull ?: return null,
                images = images,
            ).takeIf { it.images.isNotEmpty() }
        }

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
    }
}

/**
 * Looks up a past broadcast's seek previews: the video's `seekPreviewsURL` from Twitch's GraphQL API (unofficial, like
 * playback), then the storyboard description it points to. Anything that goes wrong means "no previews": callers
 * show the time alone and never let this affect playback.
 */
class SeekPreviewApi(private val http: OkHttpClient, private val config: TwitchConfig) {
    suspend fun load(videoId: String): SeekPreviews? {
        val infoUrl = previewsUrl(videoId) ?: return null
        val request = Request.Builder().url(infoUrl).build()
        http.newCall(request).await().use { response ->
            if (!response.isSuccessful) return null
            return SeekPreviews.parse(response.body.string(), infoUrl)
        }
    }

    private suspend fun previewsUrl(videoId: String): String? {
        // The id goes in as a variable, never pasted into the query text.
        val body = buildJsonObject {
            put("query", "query(\$id: ID!) { video(id: \$id) { seekPreviewsURL } }")
            put("variables", buildJsonObject { put("id", videoId) })
        }
        val request = Request.Builder()
            .url(config.gqlUrl)
            .header("Client-ID", config.webClientId)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).await().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) throw TwitchApiException(response.code, "Seek previews request failed", text)
            val video = TwitchJson.parseToJsonElement(text).jsonObject["data"]?.jsonObject?.get("video") as? JsonObject ?: return null
            return (video["seekPreviewsURL"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.startsWith("https://") }
        }
    }
}
