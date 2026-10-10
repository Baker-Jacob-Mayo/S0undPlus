package com.s0undplus.twitch.chat

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatFilterTest {
    private fun msg(login: String, text: String) = ChatMessage(
        id = "1", roomId = "r", userId = "u", login = login, displayName = login, colorHex = null,
        badges = emptyList(), text = text, isAction = false, nativeEmotes = emptyList(), timestampMillis = 0,
    )

    @Test
    fun ordinaryMessagesPass() {
        assertTrue(ChatFilter().allows(msg("alice", "hello chat")))
    }

    @Test
    fun knownBotsAreHiddenCaseInsensitivelyUnlessTheSettingIsOff() {
        assertFalse(ChatFilter().allows(msg("Nightbot", "Follow the streamer")))
        assertTrue(ChatFilter(hideBots = false).allows(msg("nightbot", "Follow the streamer")))
    }

    @Test
    fun extraBotsAreAddedToTheKnownOnes() {
        val filter = ChatFilter(extraBotLogins = setOf("MyBot"))
        assertFalse(filter.allows(msg("mybot", "hi")))
        assertFalse(filter.allows(msg("streamelements", "hi")))
    }

    @Test
    fun commandsStartWithABangAndAreHiddenUnlessTheSettingIsOff() {
        assertFalse(ChatFilter().allows(msg("alice", "!discord")))
        assertTrue(ChatFilter(hideCommands = false).allows(msg("alice", "!discord")))
        assertTrue(ChatFilter().allows(msg("alice", "wow! nice")))
    }

    @Test
    fun blockedWordsMatchAnywhereInTheMessageIgnoringCaseAndBlankEntriesAreIgnored() {
        val filter = ChatFilter(blockedWords = listOf(" Spoiler ", "", "  "))
        assertFalse(filter.allows(msg("alice", "no SPOILERS please")))
        assertTrue(filter.allows(msg("alice", "all good")))
    }

    @Test
    fun blockedUsersAreHiddenCaseInsensitivelyEvenWhenBotFilteringIsOff() {
        val filter = ChatFilter(hideBots = false, hideCommands = false, blockedUsers = setOf("Troll"))
        assertFalse(filter.allows(msg("TROLL", "hi")))
        assertTrue(filter.allows(msg("alice", "hi")))
    }
}
