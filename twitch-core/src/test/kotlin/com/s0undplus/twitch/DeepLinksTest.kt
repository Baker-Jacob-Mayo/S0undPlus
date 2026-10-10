package com.s0undplus.twitch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeepLinksTest {
    @Test
    fun parsesOwnScheme() {
        assertEquals(DeepLink.Live("shroud"), DeepLinkParser.parse("s0undplus", "live", listOf("Shroud")))
        assertEquals(DeepLink.Vod("123456"), DeepLinkParser.parse("s0undplus", "vod", listOf("123456")))
        assertEquals(DeepLink.Home, DeepLinkParser.parse("s0undplus", "home", emptyList()))
        assertNull(DeepLinkParser.parse("s0undplus", "live", emptyList()))
        assertNull(DeepLinkParser.parse("s0undplus", "vod", listOf("not-a-number")))
        assertNull(DeepLinkParser.parse("s0undplus", "nowhere", listOf("x")))
    }

    @Test
    fun parsesTwitchLinks() {
        assertEquals(DeepLink.Live("shroud"), DeepLinkParser.parse("https", "www.twitch.tv", listOf("shroud")))
        assertEquals(DeepLink.Live("shroud"), DeepLinkParser.parse("https", "twitch.tv", listOf("shroud", "about")))
        assertEquals(DeepLink.Vod("987"), DeepLinkParser.parse("https", "www.twitch.tv", listOf("videos", "987")))
    }

    @Test
    fun ignoresSitePagesAndForeignHosts() {
        assertNull(DeepLinkParser.parse("https", "www.twitch.tv", listOf("directory", "game", "Chess")))
        assertNull(DeepLinkParser.parse("https", "www.twitch.tv", listOf("settings")))
        assertNull(DeepLinkParser.parse("https", "www.twitch.tv", listOf("videos")))
        assertNull(DeepLinkParser.parse("https", "www.twitch.tv", emptyList()))
        assertNull(DeepLinkParser.parse("https", "evil.example", listOf("shroud")))
        assertNull(DeepLinkParser.parse("https", "www.twitch.tv", listOf("bad name!")))
        assertNull(DeepLinkParser.parse("ftp", "www.twitch.tv", listOf("shroud")))
        assertNull(DeepLinkParser.parse(null, null, emptyList()))
    }
}
