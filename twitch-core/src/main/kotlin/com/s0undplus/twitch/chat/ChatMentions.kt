package com.s0undplus.twitch.chat

/** Spots `@login` mentions of the signed-in viewer in chat text, so such lines can be highlighted. */
object ChatMentions {
    /**
     * True when [text] contains `@login` as a whole word (case-insensitive): `@Viewer`, `hi @viewer!` and `@viewer,`
     * count; `@viewers`, `@viewer_2` and an e-mail style `a@viewer` do not. A null or blank [login] never matches.
     */
    fun mentions(text: String, login: String?): Boolean {
        if (login.isNullOrBlank()) return false
        val needle = "@" + login.lowercase()
        val haystack = text.lowercase()
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return false
            val before = if (at == 0) ' ' else haystack[at - 1]
            val after = haystack.getOrNull(at + needle.length) ?: ' '
            if (!isNameChar(before) && !isNameChar(after)) return true
            from = at + 1
        }
    }

    private fun isNameChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'
}
