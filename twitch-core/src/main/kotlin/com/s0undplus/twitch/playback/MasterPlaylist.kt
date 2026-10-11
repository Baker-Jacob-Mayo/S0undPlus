package com.s0undplus.twitch.playback

import kotlin.math.roundToInt

/** One quality rung from a Twitch HLS master playlist. */
data class Variant(
    /** Human label as Twitch names it, e.g. `1080p60 (source)`, `720p`, `Audio only`. */
    val name: String,
    /** Playlist URL for just this rung; playable on its own. */
    val url: String,
    val bandwidth: Long,
    val width: Int,
    val height: Int,
    val frameRate: Double,
    val codecs: String,
) {
    val isAudioOnly: Boolean get() = (height == 0 && width == 0) || name.equals(AUDIO_ONLY_NAME, ignoreCase = true)
    val isSource: Boolean get() = name.contains("(source)", ignoreCase = true)

    companion object {
        const val AUDIO_ONLY_NAME = "Audio only"
    }
}

/**
 * Minimal HLS master-playlist reader — just enough for Twitch: `#EXT-X-MEDIA` rows give each rung a friendly name
 * (matched through `VIDEO="group-id"`), `#EXT-X-STREAM-INF` rows give bandwidth/resolution/codecs and are followed
 * by the media playlist URI.
 */
object MasterPlaylist {
    fun parse(text: String): List<Variant> {
        val names = HashMap<String, String>()
        val variants = mutableListOf<Variant>()
        var pending: Map<String, String>? = null

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXT-X-MEDIA:") -> {
                    val attrs = parseAttributes(line.substringAfter(':'))
                    val group = attrs["GROUP-ID"]
                    val name = attrs["NAME"]
                    if (group != null && name != null && attrs["TYPE"].equals("VIDEO", ignoreCase = true)) {
                        names[group] = name
                    }
                }
                line.startsWith("#EXT-X-STREAM-INF:") -> pending = parseAttributes(line.substringAfter(':'))
                line.startsWith("#") -> Unit
                else -> {
                    val attrs = pending
                    pending = null
                    if (attrs != null) variants += buildVariant(attrs, line, names)
                }
            }
        }
        return variants
    }

    private fun buildVariant(attrs: Map<String, String>, url: String, names: Map<String, String>): Variant {
        val (width, height) = attrs["RESOLUTION"]?.split('x', 'X')?.let { parts ->
            (parts.getOrNull(0)?.toIntOrNull() ?: 0) to (parts.getOrNull(1)?.toIntOrNull() ?: 0)
        } ?: (0 to 0)
        val group = attrs["VIDEO"]
        val frameRate = attrs["FRAME-RATE"]?.toDoubleOrNull() ?: 0.0
        // Twitch's newer (IVS) playlists name each rung in IVS-NAME (e.g. "1080p50") and flag the original with
        // IVS-VARIANT-SOURCE="source", without the EXT-X-MEDIA names older ones carried.
        val ivsName = attrs["IVS-NAME"] ?: attrs["STABLE-VARIANT-ID"]
        val fallback = when {
            height <= 0 -> Variant.AUDIO_ONLY_NAME
            frameRate > 0 -> "${height}p${frameRate.roundToInt()}"
            else -> "${height}p"
        }
        val base = when {
            group == "audio_only" || ivsName == "audio_only" -> Variant.AUDIO_ONLY_NAME
            group != null && names[group] != null -> names.getValue(group)
            ivsName != null -> ivsName
            group != null -> group
            else -> fallback
        }
        val isSource = attrs["IVS-VARIANT-SOURCE"].equals("source", ignoreCase = true)
        val name = if (isSource && !base.contains("(source)", ignoreCase = true)) "$base (source)" else base
        return Variant(
            name = name,
            url = url,
            bandwidth = attrs["BANDWIDTH"]?.toLongOrNull() ?: 0,
            width = width,
            height = height,
            frameRate = frameRate,
            codecs = attrs["CODECS"].orEmpty(),
        )
    }

    /** Parses `KEY=value,KEY2="quoted, with comma"` attribute lists. */
    internal fun parseAttributes(list: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var i = 0
        while (i < list.length) {
            val eq = list.indexOf('=', i)
            if (eq < 0) break
            val key = list.substring(i, eq).trim()
            var j = eq + 1
            val value: String
            if (j < list.length && list[j] == '"') {
                val close = list.indexOf('"', j + 1).let { if (it < 0) list.length else it }
                value = list.substring(j + 1, close)
                j = close + 1
            } else {
                val comma = list.indexOf(',', j).let { if (it < 0) list.length else it }
                value = list.substring(j, comma).trim()
                j = comma
            }
            out[key] = value
            i = if (j < list.length && list[j] == ',') j + 1 else j
        }
        return out
    }
}
