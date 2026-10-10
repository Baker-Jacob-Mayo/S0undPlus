package com.s0undplus.tv.data

import com.s0undplus.tv.ui.player.adSources
import com.s0undplus.twitch.playback.PlaybackRoute
import com.s0undplus.twitch.playback.ProxyDefaults
import com.s0undplus.twitch.playback.ProxyEntryCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsTest {
    @Test
    fun adHandlingIsOffByDefaultAndNeedsNoSources() {
        val s = AppSettings()
        assertEquals(AdMode.OFF, s.adMode)
        assertTrue(s.adSources().isEmpty())
        assertTrue(s.copy(adMode = AdMode.HIDE).adSources().isEmpty())
    }

    @Test
    fun theDefaultProxyListYieldsOnlyTheEnabledEntriesInOrder() {
        val sources = AppSettings(adMode = AdMode.PROXY).adSources()
        val enabled = ProxyDefaults.customProxies.filter { it.enabled }
        assertEquals(enabled.size, sources.size)
        assertEquals("eu.luminous.dev", sources.first().label)
        assertTrue(sources.all { it.options.route is PlaybackRoute.CustomUrl })
        assertEquals(enabled.map { it.template }, sources.map { (it.options.route as PlaybackRoute.CustomUrl).template })
    }

    @Test
    fun disabledAndJunkProxyLinesAreSkipped() {
        val s = AppSettings(adMode = AdMode.PROXY, adProxies = "#off.example/live/\$channel\nbad line\non.example/live/\$channel")
        assertEquals(listOf("on.example"), s.adSources().map { it.label })
    }

    @Test
    fun anEmptyProxyListMeansNoSources() {
        assertTrue(AppSettings(adMode = AdMode.PROXY, adProxies = "").adSources().isEmpty())
    }

    @Test
    fun httpProxyModeNeedsACompleteHostAndPort() {
        val ok = AppSettings(adMode = AdMode.HTTP_PROXY).adSources()
        assertEquals(1, ok.size)
        assertEquals(ProxyDefaults.httpProxy.host, ok.single().label)
        assertTrue(ok.single().options.route is PlaybackRoute.Http)
        assertTrue(AppSettings(adMode = AdMode.HTTP_PROXY, adHttpHost = "  ").adSources().isEmpty())
        assertTrue(AppSettings(adMode = AdMode.HTTP_PROXY, adHttpPort = 0).adSources().isEmpty())
        assertNull(AppSettings(adHttpPort = 70_000).httpProxy)
    }

    @Test
    fun httpProxyCarriesCredentialsAndToggles() {
        val proxy = AppSettings(
            adHttpHost = " p.example ", adHttpPort = 8080, adHttpUser = "u", adHttpPassword = "pw",
            adHttpToken = true, adHttpMultivariant = true, adHttpMedia = false,
        ).httpProxy!!
        assertEquals("p.example", proxy.host)
        assertEquals("u", proxy.username)
        assertEquals("pw", proxy.password)
        assertTrue(proxy.proxyToken && proxy.proxyMultivariant && !proxy.proxyMedia)
        assertNull(AppSettings(adHttpUser = "").httpProxy?.username)
    }

    @Test
    fun videoSwapDefaultsToTheAndroidAutoplayIdentityOnly() {
        val sources = AppSettings(adMode = AdMode.VIDEO_SWAP).adSources()
        assertEquals(1, sources.size)
        assertEquals("android", sources.single().options.platform)
        assertEquals("autoplay", sources.single().options.playerType)
        val all = AppSettings(
            adMode = AdMode.VIDEO_SWAP,
            adSwap = ProxyEntryCodec.encodeSwap(ProxyDefaults.videoSwap.map { it.copy(enabled = true) }),
        ).adSources()
        assertEquals(3, all.size)
    }

    @Test
    fun theTokenPlayerTypeOnlyAppliesWhileAdHandlingIsOn() {
        assertEquals("site", AppSettings(adMode = AdMode.OFF, tokenPlayerType = "embed").effectivePlayerType)
        assertEquals("embed", AppSettings(adMode = AdMode.HIDE, tokenPlayerType = "embed").effectivePlayerType)
        assertEquals("site", AppSettings(adMode = AdMode.HIDE, tokenPlayerType = "bogus").effectivePlayerType)
        val proxied = AppSettings(adMode = AdMode.PROXY, tokenPlayerType = "popout").adSources().first()
        assertEquals("popout", proxied.options.playerType)
    }

    @Test
    fun chatFilterIsBuiltFromTheCommaOrNewlineSeparatedLists() {
        val filter = AppSettings(blockedWords = "spoiler, giveaway\nraffle", blockedUsers = "Troll,  bot2 ").chatFilter
        assertEquals(listOf("spoiler", "giveaway", "raffle"), filter.blockedWords)
        assertEquals(setOf("Troll", "bot2"), filter.blockedUsers)
    }
}
