package com.s0undplus.twitch.chat

import java.util.Locale

/** Viewer-side chat filtering (S0undTV's "filters for words, users, and bots"). Pure and cheap: runs per message. */
data class ChatFilter(
    val hideBots: Boolean = true,
    val extraBotLogins: Set<String> = emptySet(),
    val hideCommands: Boolean = true,
    val blockedWords: List<String> = emptyList(),
    val blockedUsers: Set<String> = emptySet(),
) {
    private val bots: Set<String> = KNOWN_BOTS + extraBotLogins.map { it.lowercase(Locale.ROOT) }
    private val users: Set<String> = blockedUsers.map { it.lowercase(Locale.ROOT) }.toSet()
    private val words: List<String> = blockedWords.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }

    fun allows(message: ChatMessage): Boolean {
        val login = message.login.lowercase(Locale.ROOT)
        if (login in users) return false
        if (hideBots && login in bots) return false
        if (hideCommands && message.text.startsWith("!")) return false
        if (words.isNotEmpty()) {
            val text = message.text.lowercase(Locale.ROOT)
            if (words.any { text.contains(it) }) return false
        }
        return true
    }

    companion object {
        val KNOWN_BOTS: Set<String> = setOf(
            "nightbot", "streamelements", "streamlabs", "moobot", "fossabot", "wizebot", "sery_bot", "soundalerts",
            "commanderroot", "pokemoncommunitygame", "stay_hydrated_bot", "lurxx", "anotherttvviewer",
        )
    }
}
