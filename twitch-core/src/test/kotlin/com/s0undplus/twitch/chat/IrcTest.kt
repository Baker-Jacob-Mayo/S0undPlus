package com.s0undplus.twitch.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IrcTest {
    private val privmsg =
        "@badge-info=subscriber/8;badges=moderator/1,subscriber/6;color=#1E90FF;display-name=Some\\sOne;" +
            "emotes=25:0-4,12-16/1902:6-10;id=abc-123;room-id=1337;tmi-sent-ts=1700000000123;user-id=42 " +
            ":someone!someone@someone.tmi.twitch.tv PRIVMSG #channel :Kappa Keepo Kappa hello"

    @Test
    fun parsesTagsPrefixCommandAndTrailing() {
        val irc = assertNotNull(IrcParser.parse(privmsg))
        assertEquals("PRIVMSG", irc.command)
        assertEquals("someone!someone@someone.tmi.twitch.tv", irc.prefix)
        assertEquals(listOf("#channel", "Kappa Keepo Kappa hello"), irc.params)
        assertEquals("Some One", irc.tags["display-name"]) // \s unescaped
        assertEquals("1337", irc.tags["room-id"])
    }

    @Test
    fun unescapesAllTagEscapes() {
        assertEquals("a;b c\\d\r\ne", IrcParser.unescapeTag("a\\:b\\sc\\\\d\\r\\ne"))
    }

    @Test
    fun readsBitsAndReplyContextFromTheTags() {
        val line = "@bits=500;display-name=Fan;id=m1;reply-parent-display-name=Streamer\\sName;" +
            "reply-parent-msg-body=what\\sgame\\s:)\\sis\\sthis;room-id=1;user-id=2 " +
            ":fan!fan@fan.tmi.twitch.tv PRIVMSG #channel :@Streamer_Name this one cheer500"
        val msg = assertNotNull(ChatProtocol.parseMessage(assertNotNull(IrcParser.parse(line))))
        assertEquals(500, msg.bits)
        assertEquals("Streamer Name", msg.replyToName)
        assertEquals("what game :) is this", msg.replyToBody)
        val plain = assertNotNull(ChatProtocol.parseMessage(assertNotNull(IrcParser.parse(privmsg))))
        assertNull(plain.replyToName)
        assertNull(plain.replyToBody)
        assertEquals(0, plain.bits)
    }

    @Test
    fun parsesLinesWithoutTagsOrPrefix() {
        val irc = assertNotNull(IrcParser.parse("PING :tmi.twitch.tv\r\n"))
        assertEquals("PING", irc.command)
        assertEquals("tmi.twitch.tv", irc.trailing)
        assertNull(IrcParser.parse(""))
        assertNull(IrcParser.parse("@only=tags"))
    }

    @Test
    fun buildsChatMessageFromPrivmsg() {
        val msg = assertNotNull(ChatProtocol.parseMessage(assertNotNull(IrcParser.parse(privmsg))))
        assertEquals("abc-123", msg.id)
        assertEquals("someone", msg.login)
        assertEquals("Some One", msg.displayName)
        assertEquals("#1E90FF", msg.colorHex)
        assertEquals("1337", msg.roomId)
        assertEquals("42", msg.userId)
        assertEquals(1700000000123L, msg.timestampMillis)
        assertEquals(listOf(BadgeRef("moderator", "1"), BadgeRef("subscriber", "6")), msg.badges)
        assertEquals(
            listOf(NativeEmote("25", 0, 4), NativeEmote("1902", 6, 10), NativeEmote("25", 12, 16)),
            msg.nativeEmotes,
        )
        assertFalse(msg.isAction)
    }

    @Test
    fun detectsActionMessages() {
        val line = ":bob!bob@bob.tmi.twitch.tv PRIVMSG #c :\u0001ACTION waves\u0001"
        val msg = assertNotNull(ChatProtocol.parseMessage(assertNotNull(IrcParser.parse(line))))
        assertTrue(msg.isAction)
        assertEquals("waves", msg.text)
        assertEquals("bob", msg.displayName) // falls back to login without a display-name tag
    }

    @Test
    fun answersPingWithPong() {
        val action = ChatProtocol.handle("PING :tmi.twitch.tv")
        assertEquals(ProtocolAction.Reply("PONG :tmi.twitch.tv"), action)
    }

    @Test
    fun mapsRoomstateToConnected() {
        val action = ChatProtocol.handle("@room-id=99;slow=0 :tmi.twitch.tv ROOMSTATE #channel")
        assertEquals(ProtocolAction.Emit(ChatEvent.Connected("99")), action)
    }

    @Test
    fun mapsModerationAndNotices() {
        assertEquals(
            ProtocolAction.Emit(ChatEvent.ClearChat("troll")),
            ChatProtocol.handle(":tmi.twitch.tv CLEARCHAT #channel :troll"),
        )
        assertEquals(
            ProtocolAction.Emit(ChatEvent.ClearChat(null)),
            ChatProtocol.handle(":tmi.twitch.tv CLEARCHAT #channel"),
        )
        assertEquals(
            ProtocolAction.Emit(ChatEvent.ClearMessage("m-1")),
            ChatProtocol.handle("@login=troll;target-msg-id=m-1 :tmi.twitch.tv CLEARMSG #channel :spam"),
        )
        val sub = ChatProtocol.handle(
            "@msg-id=sub;system-msg=Bob\\ssubscribed. :tmi.twitch.tv USERNOTICE #channel :great stream",
        )
        assertEquals(ProtocolAction.Emit(ChatEvent.Notice("Bob subscribed. — great stream")), sub)
        assertEquals(ProtocolAction.Reconnect, ChatProtocol.handle(":tmi.twitch.tv RECONNECT"))
        assertNull(ChatProtocol.handle(":tmi.twitch.tv 001 justinfan1 :Welcome, GLHF!"))
    }

    @Test
    fun loginSequenceIsAnonymousAndLowercasesChannel() {
        val lines = ChatProtocol.loginLines("justinfan12345", "SomeChannel")
        assertEquals("CAP REQ :twitch.tv/tags twitch.tv/commands", lines[0])
        assertEquals("PASS SCHMOOPIIE", lines[1])
        assertEquals("NICK justinfan12345", lines[2])
        assertEquals("JOIN #somechannel", lines[3])
    }

    @Test
    fun privmsgEventIsEmitted() {
        val action = assertIs<ProtocolAction.Emit>(ChatProtocol.handle(privmsg))
        assertIs<ChatEvent.Message>(action.event)
    }
}
