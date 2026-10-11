package com.s0undplus.twitch.chat

/** A parsed IRC line: `@tags :prefix COMMAND param param :trailing`. The trailing text is the last entry of [params]. */
data class IrcMessage(
    val tags: Map<String, String>,
    val prefix: String?,
    val command: String,
    val params: List<String>,
) {
    val trailing: String? get() = params.lastOrNull()
}

object IrcParser {
    fun parse(line: String): IrcMessage? {
        var rest = line.trimEnd('\r', '\n')
        if (rest.isBlank()) return null

        var tags: Map<String, String> = emptyMap()
        if (rest.startsWith('@')) {
            val space = rest.indexOf(' ')
            if (space < 0) return null
            tags = parseTags(rest.substring(1, space))
            rest = rest.substring(space + 1).trimStart(' ')
        }

        var prefix: String? = null
        if (rest.startsWith(':')) {
            val space = rest.indexOf(' ')
            if (space < 0) return null
            prefix = rest.substring(1, space)
            rest = rest.substring(space + 1).trimStart(' ')
        }

        val commandEnd = rest.indexOf(' ').let { if (it < 0) rest.length else it }
        val command = rest.substring(0, commandEnd)
        if (command.isEmpty()) return null
        rest = rest.substring(commandEnd).trimStart(' ')

        val params = mutableListOf<String>()
        while (rest.isNotEmpty()) {
            if (rest.startsWith(':')) {
                params += rest.substring(1)
                break
            }
            val space = rest.indexOf(' ')
            if (space < 0) {
                params += rest
                break
            }
            params += rest.substring(0, space)
            rest = rest.substring(space + 1).trimStart(' ')
        }
        return IrcMessage(tags, prefix, command, params)
    }

    private fun parseTags(raw: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (pair in raw.split(';')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            if (eq < 0) out[pair] = "" else out[pair.substring(0, eq)] = unescapeTag(pair.substring(eq + 1))
        }
        return out
    }

    /** IRCv3 tag value escapes: `\:` `\s` `\\` `\r` `\n`. */
    internal fun unescapeTag(value: String): String {
        if (!value.contains('\\')) return value
        val sb = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                when (val n = value[i + 1]) {
                    ':' -> sb.append(';')
                    's' -> sb.append(' ')
                    'r' -> sb.append('\r')
                    'n' -> sb.append('\n')
                    else -> sb.append(n)
                }
                i += 2
            } else {
                if (c != '\\') sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}

data class BadgeRef(val setId: String, val version: String)

/** A Twitch-native emote occurrence. [start]/[end] are inclusive indices in Unicode *code points*, not UTF-16 units. */
data class NativeEmote(val id: String, val start: Int, val end: Int)

data class ChatMessage(
    val id: String,
    val roomId: String,
    val userId: String,
    val login: String,
    val displayName: String,
    /** `#RRGGBB` or null when the user never picked a color. */
    val colorHex: String?,
    val badges: List<BadgeRef>,
    val text: String,
    /** `/me` message: rendered in the user's color. */
    val isAction: Boolean,
    val nativeEmotes: List<NativeEmote>,
    val timestampMillis: Long,
    val isHighlighted: Boolean = false,
    val bits: Int = 0,
    /** For VOD chat replay: when this message was sent, relative to the start of the video. Null for live chat. */
    val vodOffsetMs: Long? = null,
    /** When this message is a reply: the display name of the message it answers, and (shortened by Twitch) its text. */
    val replyToName: String? = null,
    val replyToBody: String? = null,
)

sealed interface ChatEvent {
    /** Joined the room; [roomId] is the broadcaster's user id. */
    data class Connected(val roomId: String?) : ChatEvent

    data class Message(val message: ChatMessage) : ChatEvent

    /** Server notices and sub/raid/etc. announcements, already flattened to display text. */
    data class Notice(val text: String) : ChatEvent

    /** A user was timed out/banned ([targetLogin]) or the whole chat was cleared (null). */
    data class ClearChat(val targetLogin: String?) : ChatEvent

    data class ClearMessage(val messageId: String) : ChatEvent

    data class Disconnected(val willRetry: Boolean) : ChatEvent
}

/** What the socket layer should do after feeding one line to [ChatProtocol.handle]. */
sealed interface ProtocolAction {
    data class Reply(val line: String) : ProtocolAction
    data class Emit(val event: ChatEvent) : ProtocolAction

    /** Twitch asked us to reconnect (server maintenance). */
    data object Reconnect : ProtocolAction
}

/** Pure translation of raw IRC lines into [ProtocolAction]s, so the interesting logic is unit-testable. */
object ChatProtocol {
    const val ANONYMOUS_PASS = "SCHMOOPIIE"

    fun loginLines(nick: String, channel: String): List<String> = listOf(
        "CAP REQ :twitch.tv/tags twitch.tv/commands",
        "PASS $ANONYMOUS_PASS",
        "NICK $nick",
        "JOIN #${channel.lowercase()}",
    )

    fun handle(line: String): ProtocolAction? {
        val irc = IrcParser.parse(line) ?: return null
        return when (irc.command) {
            "PING" -> ProtocolAction.Reply("PONG :${irc.trailing ?: "tmi.twitch.tv"}")
            "RECONNECT" -> ProtocolAction.Reconnect
            "ROOMSTATE" -> ProtocolAction.Emit(ChatEvent.Connected(irc.tags["room-id"]))
            "PRIVMSG" -> parseMessage(irc)?.let { ProtocolAction.Emit(ChatEvent.Message(it)) }
            "NOTICE" -> irc.trailing?.let { ProtocolAction.Emit(ChatEvent.Notice(it)) }
            "USERNOTICE" -> {
                val system = irc.tags["system-msg"].orEmpty()
                val said = irc.params.getOrNull(1).orEmpty()
                val text = listOf(system, said).filter { it.isNotBlank() }.joinToString(" — ")
                if (text.isEmpty()) null else ProtocolAction.Emit(ChatEvent.Notice(text))
            }
            "CLEARCHAT" -> ProtocolAction.Emit(ChatEvent.ClearChat(irc.params.getOrNull(1)))
            "CLEARMSG" -> irc.tags["target-msg-id"]?.let { ProtocolAction.Emit(ChatEvent.ClearMessage(it)) }
            else -> null
        }
    }

    /** Parses a `PRIVMSG` (also used for lines from the recent-messages service). */
    fun parseMessage(irc: IrcMessage): ChatMessage? {
        if (irc.command != "PRIVMSG") return null
        var text = irc.params.getOrNull(1) ?: return null
        val login = irc.prefix?.substringBefore('!')?.takeIf { it.isNotEmpty() } ?: return null

        var isAction = false
        if (text.startsWith(ACTION_PREFIX) && text.endsWith("\u0001")) {
            isAction = true
            text = text.substring(ACTION_PREFIX.length, text.length - 1)
        }
        val tags = irc.tags
        return ChatMessage(
            id = tags["id"].orEmpty(),
            roomId = tags["room-id"].orEmpty(),
            userId = tags["user-id"].orEmpty(),
            login = login,
            displayName = tags["display-name"]?.takeIf { it.isNotBlank() } ?: login,
            colorHex = tags["color"]?.takeIf { it.isNotBlank() },
            badges = parseBadges(tags["badges"]),
            text = text,
            isAction = isAction,
            nativeEmotes = parseEmotes(tags["emotes"]),
            timestampMillis = tags["tmi-sent-ts"]?.toLongOrNull() ?: 0L,
            isHighlighted = tags["msg-id"] == "highlighted-message",
            bits = tags["bits"]?.toIntOrNull() ?: 0,
            replyToName = tags["reply-parent-display-name"]?.takeIf { it.isNotBlank() },
            replyToBody = tags["reply-parent-msg-body"]?.takeIf { it.isNotBlank() },
        )
    }

    /** `moderator/1,subscriber/12` → refs. */
    internal fun parseBadges(raw: String?): List<BadgeRef> =
        raw.orEmpty().split(',').mapNotNull { entry ->
            val slash = entry.indexOf('/')
            if (slash <= 0) null else BadgeRef(entry.substring(0, slash), entry.substring(slash + 1))
        }

    /** `25:0-4,12-16/1902:6-10` → occurrences sorted by position. */
    internal fun parseEmotes(raw: String?): List<NativeEmote> {
        if (raw.isNullOrEmpty()) return emptyList()
        val out = mutableListOf<NativeEmote>()
        for (group in raw.split('/')) {
            val colon = group.indexOf(':')
            if (colon <= 0) continue
            val id = group.substring(0, colon)
            for (range in group.substring(colon + 1).split(',')) {
                val dash = range.indexOf('-')
                val start = range.substring(0, if (dash < 0) range.length else dash).toIntOrNull()
                val end = if (dash < 0) null else range.substring(dash + 1).toIntOrNull()
                if (start != null && end != null) out += NativeEmote(id, start, end)
            }
        }
        return out.sortedBy { it.start }
    }

    private const val ACTION_PREFIX = "\u0001ACTION "
}
