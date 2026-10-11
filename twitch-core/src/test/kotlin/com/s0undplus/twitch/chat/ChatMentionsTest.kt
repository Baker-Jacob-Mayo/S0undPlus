package com.s0undplus.twitch.chat

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatMentionsTest {
    @Test
    fun matchesTheLoginAsAWholeWordIgnoringCase() {
        assertTrue(ChatMentions.mentions("@Viewer hello", "viewer"))
        assertTrue(ChatMentions.mentions("hi @viewer!", "Viewer"))
        assertTrue(ChatMentions.mentions("thanks @viewer, nice", "viewer"))
        assertTrue(ChatMentions.mentions("see you @viewer", "viewer"))
    }

    @Test
    fun doesNotMatchLongerNamesOrEmbeddedAts() {
        assertFalse(ChatMentions.mentions("@viewers hello", "viewer"))
        assertFalse(ChatMentions.mentions("@viewer_2 hello", "viewer"))
        assertFalse(ChatMentions.mentions("mail me at a@viewer", "viewer"))
        assertFalse(ChatMentions.mentions("viewer without an at sign", "viewer"))
    }

    @Test
    fun findsALaterMatchAfterAnEarlierNearMiss() {
        assertTrue(ChatMentions.mentions("@viewers and also @viewer", "viewer"))
    }

    @Test
    fun aMissingLoginNeverMatches() {
        assertFalse(ChatMentions.mentions("@ hello @", null))
        assertFalse(ChatMentions.mentions("@ hello", ""))
        assertFalse(ChatMentions.mentions("@ hello", "  "))
    }
}
