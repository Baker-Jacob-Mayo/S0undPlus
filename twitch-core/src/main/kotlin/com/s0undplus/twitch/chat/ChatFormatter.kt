package com.s0undplus.twitch.chat

/** A renderable piece of a chat message: plain text or an inline emote image. */
sealed interface ChatSegment {
    data class Text(val text: String) : ChatSegment
    data class EmoteImage(val name: String, val url: String, val provider: EmoteProvider) : ChatSegment
}

object ChatFormatter {
    /**
     * Splits [message] into text and emote segments. Twitch-native emotes come from the message's `emotes` tag
     * (positions are Unicode code points, so emoji earlier in the line don't shift them); everything else is checked
     * word-by-word against the 7TV/BTTV/FFZ [thirdParty] directory.
     */
    fun format(message: ChatMessage, thirdParty: EmoteDirectory = EmoteDirectory.EMPTY): List<ChatSegment> {
        val cps = message.text.codePoints().toArray()
        val out = mutableListOf<ChatSegment>()

        var cursor = 0
        for (emote in message.nativeEmotes) {
            // Defensive: skip ranges that are out of bounds or overlap the previous one instead of crashing chat.
            if (emote.start < cursor || emote.end < emote.start || emote.end >= cps.size) continue
            if (emote.start > cursor) out += splitWords(String(cps, cursor, emote.start - cursor), thirdParty)
            out += ChatSegment.EmoteImage(
                name = String(cps, emote.start, emote.end - emote.start + 1),
                url = twitchEmoteUrl(emote.id),
                provider = EmoteProvider.TWITCH,
            )
            cursor = emote.end + 1
        }
        if (cursor < cps.size) out += splitWords(String(cps, cursor, cps.size - cursor), thirdParty)
        return mergeText(out)
    }

    private fun splitWords(text: String, directory: EmoteDirectory): List<ChatSegment> {
        if (directory.size == 0) return listOf(ChatSegment.Text(text))
        val out = mutableListOf<ChatSegment>()
        val buffer = StringBuilder()
        var i = 0
        while (i < text.length) {
            if (text[i].isWhitespace()) {
                buffer.append(text[i])
                i++
                continue
            }
            var j = i
            while (j < text.length && !text[j].isWhitespace()) j++
            val word = text.substring(i, j)
            val emote = directory[word]
            if (emote == null) {
                buffer.append(word)
            } else {
                if (buffer.isNotEmpty()) {
                    out += ChatSegment.Text(buffer.toString())
                    buffer.clear()
                }
                out += ChatSegment.EmoteImage(emote.name, emote.url, emote.provider)
            }
            i = j
        }
        if (buffer.isNotEmpty()) out += ChatSegment.Text(buffer.toString())
        return out
    }

    private fun mergeText(segments: List<ChatSegment>): List<ChatSegment> {
        val out = mutableListOf<ChatSegment>()
        for (s in segments) {
            val last = out.lastOrNull()
            if (s is ChatSegment.Text && last is ChatSegment.Text) {
                out[out.lastIndex] = ChatSegment.Text(last.text + s.text)
            } else {
                out += s
            }
        }
        return out
    }
}
