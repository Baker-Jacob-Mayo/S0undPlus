package com.s0undplus.twitch

/** Broadcast languages offered for the "Language" row (ISO 639-1 codes, as Helix's `language` filter expects). */
object TwitchLanguages {
    val ALL: List<Pair<String, String>> = listOf(
        "en" to "English",
        "es" to "Español",
        "pt" to "Português",
        "fr" to "Français",
        "de" to "Deutsch",
        "it" to "Italiano",
        "nl" to "Nederlands",
        "sv" to "Svenska",
        "da" to "Dansk",
        "fi" to "Suomi",
        "no" to "Norsk",
        "pl" to "Polski",
        "cs" to "Čeština",
        "hu" to "Magyar",
        "ro" to "Română",
        "ru" to "Русский",
        "uk" to "Українська",
        "tr" to "Türkçe",
        "ar" to "العربية",
        "ja" to "日本語",
        "ko" to "한국어",
        "zh" to "中文",
        "th" to "ไทย",
        "vi" to "Tiếng Việt",
        "id" to "Bahasa Indonesia",
        "hi" to "हिन्दी",
    )

    fun name(code: String): String = ALL.firstOrNull { it.first == code }?.second ?: code

    /** The viewer's device language if Twitch has a matching broadcast language, else English. */
    fun defaultFor(deviceLanguage: String): String =
        deviceLanguage.lowercase().takeIf { code -> ALL.any { it.first == code } } ?: "en"

    /** Next code in [ALL] after [current], wrapping around — for a settings row that cycles on click. */
    fun next(current: String): String {
        val i = ALL.indexOfFirst { it.first == current }
        return ALL[(i + 1) % ALL.size].first
    }
}
