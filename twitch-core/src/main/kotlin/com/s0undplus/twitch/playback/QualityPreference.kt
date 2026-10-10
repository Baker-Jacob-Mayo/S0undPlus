package com.s0undplus.twitch.playback

/**
 * The viewer's remembered quality choice, resolved against whatever rungs a particular stream offers
 * (not every channel has 1080p60, so the same preference can map to different variants).
 */
object QualityPreference {
    const val AUTO = "auto"
    const val SOURCE = "source"

    /** Choices offered in settings, best first. Numbers are target heights. */
    val OPTIONS: List<String> = listOf(AUTO, SOURCE, "1080", "720", "480", "360")

    /**
     * Returns the variant to pin for [preference], or null for adaptive playback ([AUTO], an unknown preference, or
     * a stream with no video rungs). A numeric preference picks the best frame rate at that height, else the tallest
     * rung below it, else the smallest available.
     */
    fun pick(variants: List<Variant>, preference: String): Variant? {
        val video = variants.filter { !it.isAudioOnly }
        if (video.isEmpty() || preference == AUTO) return null
        if (preference == SOURCE) return video.firstOrNull { it.isSource } ?: video.maxBy { it.bandwidth }
        val target = preference.toIntOrNull() ?: return null
        video.filter { it.height == target }.maxByOrNull { it.frameRate }?.let { return it }
        return video.filter { it.height < target }.maxWithOrNull(compareBy<Variant> { it.height }.thenBy { it.frameRate })
            ?: video.minBy { it.height }
    }

    fun label(preference: String): String = when (preference) {
        AUTO -> "Auto"
        SOURCE -> "Source"
        else -> "${preference}p"
    }
}
