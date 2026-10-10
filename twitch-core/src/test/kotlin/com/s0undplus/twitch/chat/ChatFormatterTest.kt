package com.s0undplus.twitch.chat

import kotlin.test.Test
import kotlin.test.assertEquals

class ChatFormatterTest {
    private fun message(text: String, emotes: List<NativeEmote> = emptyList()) = ChatMessage(
        id = "1", roomId = "r", userId = "u", login = "bob", displayName = "Bob", colorHex = null,
        badges = emptyList(), text = text, isAction = false, nativeEmotes = emotes, timestampMillis = 0,
    )

    private val kappa = twitchEmoteUrl("25")

    @Test
    fun plainTextIsOneSegment() {
        assertEquals(listOf(ChatSegment.Text("hello world")), ChatFormatter.format(message("hello world")))
    }

    @Test
    fun nativeEmotesSplitTheText() {
        val segments = ChatFormatter.format(message("hi Kappa there", listOf(NativeEmote("25", 3, 7))))
        assertEquals(
            listOf(
                ChatSegment.Text("hi "),
                ChatSegment.EmoteImage("Kappa", kappa, EmoteProvider.TWITCH),
                ChatSegment.Text(" there"),
            ),
            segments,
        )
    }

    @Test
    fun emoteIndicesCountCodePointsNotUtf16Units() {
        // "😀" is one code point but two UTF-16 chars; Twitch reports Kappa at code points 2-6.
        val segments = ChatFormatter.format(message("😀 Kappa", listOf(NativeEmote("25", 2, 6))))
        assertEquals(
            listOf(
                ChatSegment.Text("😀 "),
                ChatSegment.EmoteImage("Kappa", kappa, EmoteProvider.TWITCH),
            ),
            segments,
        )
    }

    @Test
    fun badRangesAreSkippedInsteadOfCrashing() {
        val segments = ChatFormatter.format(
            message("abc", listOf(NativeEmote("25", 5, 9), NativeEmote("25", 2, 1))),
        )
        assertEquals(listOf(ChatSegment.Text("abc")), segments)
    }

    @Test
    fun thirdPartyEmotesMatchWholeWordsOnly() {
        val directory = EmoteDirectory.merge(
            listOf(
                Emote("OMEGALUL", "https://cdn/omega", EmoteProvider.BTTV),
                Emote("EZ", "https://cdn/ez", EmoteProvider.SEVENTV),
            ),
        )
        val segments = ChatFormatter.format(message("gg EZ EZClap OMEGALUL"), directory)
        assertEquals(
            listOf(
                ChatSegment.Text("gg "),
                ChatSegment.EmoteImage("EZ", "https://cdn/ez", EmoteProvider.SEVENTV),
                ChatSegment.Text(" EZClap "),
                ChatSegment.EmoteImage("OMEGALUL", "https://cdn/omega", EmoteProvider.BTTV),
            ),
            segments,
        )
    }

    @Test
    fun laterProvidersOverrideEarlierOnes() {
        val directory = EmoteDirectory.merge(
            listOf(Emote("X", "ffz", EmoteProvider.FFZ)),
            listOf(Emote("X", "seven", EmoteProvider.SEVENTV)),
        )
        assertEquals("seven", directory["X"]?.url)
    }

    @Test
    fun filterHidesBotsCommandsWordsAndUsers() {
        val filter = ChatFilter(blockedWords = listOf("spoiler"), blockedUsers = setOf("Troll"))
        fun msg(login: String, text: String) = message(text).copy(login = login)
        assertEquals(true, filter.allows(msg("alice", "hello")))
        assertEquals(false, filter.allows(msg("nightbot", "hello")))
        assertEquals(false, filter.allows(msg("alice", "!discord")))
        assertEquals(false, filter.allows(msg("alice", "Big SPOILER ahead")))
        assertEquals(false, filter.allows(msg("TROLL", "hello")))
        assertEquals(true, ChatFilter(hideBots = false, hideCommands = false).allows(msg("nightbot", "!x")))
    }
}
