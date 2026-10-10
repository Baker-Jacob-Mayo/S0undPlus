package com.s0undplus.twitch.chat

import com.s0undplus.twitch.BadgeSetDto
import com.s0undplus.twitch.BadgeVersionDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BadgeDirectoryTest {
    private fun set(id: String, vararg versions: BadgeVersionDto) = BadgeSetDto(id, versions.toList())

    @Test
    fun resolvesBySetAndVersionPreferringTheTwoTimesImage() {
        val dir = BadgeDirectory.from(
            global = listOf(set("moderator", BadgeVersionDto("1", image1x = "mod-1x", image2x = "mod-2x"))),
            channel = emptyList(),
        )
        assertEquals("mod-2x", dir.url(BadgeRef("moderator", "1")))
    }

    @Test
    fun fallsBackToTheOneTimesImageAndSkipsVersionsWithNoImage() {
        val dir = BadgeDirectory.from(
            global = listOf(
                set("vip", BadgeVersionDto("1", image1x = "vip-1x")),
                set("broken", BadgeVersionDto("1")),
            ),
            channel = emptyList(),
        )
        assertEquals("vip-1x", dir.url(BadgeRef("vip", "1")))
        assertNull(dir.url(BadgeRef("broken", "1")))
    }

    @Test
    fun channelBadgesOverrideGlobalOnesWithTheSameIdAndVersionOnly() {
        val dir = BadgeDirectory.from(
            global = listOf(set("subscriber", BadgeVersionDto("0", image2x = "global-0"), BadgeVersionDto("6", image2x = "global-6"))),
            channel = listOf(set("subscriber", BadgeVersionDto("0", image2x = "custom-0"))),
        )
        assertEquals("custom-0", dir.url(BadgeRef("subscriber", "0")))
        assertEquals("global-6", dir.url(BadgeRef("subscriber", "6")))
    }

    @Test
    fun unknownBadgesAndTheEmptyDirectoryResolveToNull() {
        assertNull(BadgeDirectory.EMPTY.url(BadgeRef("moderator", "1")))
        assertNull(BadgeDirectory.from(emptyList(), emptyList()).url(BadgeRef("x", "y")))
    }
}
