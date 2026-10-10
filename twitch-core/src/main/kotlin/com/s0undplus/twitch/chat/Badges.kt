package com.s0undplus.twitch.chat

import com.s0undplus.twitch.BadgeSetDto

/** Resolves a message's `moderator/1`-style badge refs to image URLs. */
class BadgeDirectory private constructor(private val urls: Map<String, String>) {
    fun url(badge: BadgeRef): String? = urls["${badge.setId}/${badge.version}"]

    companion object {
        val EMPTY = BadgeDirectory(emptyMap())

        /** Channel badges (custom sub/bits badges) override the global set of the same id+version. */
        internal fun from(global: List<BadgeSetDto>, channel: List<BadgeSetDto>): BadgeDirectory {
            val map = HashMap<String, String>()
            for (set in global + channel) {
                for (v in set.versions) {
                    val image = v.image2x.ifEmpty { v.image1x }
                    if (image.isNotEmpty()) map["${set.setId}/${v.id}"] = image
                }
            }
            return BadgeDirectory(map)
        }
    }
}
