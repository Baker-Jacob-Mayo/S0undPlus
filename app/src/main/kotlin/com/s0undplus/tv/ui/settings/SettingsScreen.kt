package com.s0undplus.tv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.s0undplus.tv.BuildConfig
import com.s0undplus.tv.LocalAppContainer
import com.s0undplus.tv.data.AdMode
import com.s0undplus.tv.data.AppSettings
import com.s0undplus.tv.data.AppTheme
import com.s0undplus.tv.data.ChatLayout
import com.s0undplus.tv.data.DeletedStyle
import com.s0undplus.tv.ui.components.FocusSurface
import com.s0undplus.tv.ui.components.LoadingState
import com.s0undplus.tv.ui.components.PillButton
import com.s0undplus.tv.ui.components.TvTextField
import com.s0undplus.tv.ui.theme.Palette
import com.s0undplus.tv.ui.update.UpdateUi
import com.s0undplus.tv.ui.update.UpdateViewModel
import com.s0undplus.tv.ui.update.statusLabel
import com.s0undplus.twitch.TwitchLanguages
import com.s0undplus.twitch.playback.PlayerTypes
import com.s0undplus.twitch.playback.ProxyDefaults
import com.s0undplus.twitch.playback.ProxyEntry
import com.s0undplus.twitch.playback.ProxyEntryCodec
import com.s0undplus.twitch.playback.ProxyHealth
import com.s0undplus.twitch.playback.ProxyUrls
import com.s0undplus.twitch.playback.QualityPreference
import com.s0undplus.twitch.playback.RefreshMode
import com.s0undplus.twitch.playback.VideoSwapEntry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val SIZE_LABELS = listOf("Small", "Medium", "Large")

@Composable
fun SettingsContent(updates: UpdateViewModel) {
    val container = LocalAppContainer.current
    val initial by produceState<AppSettings?>(null) { value = container.settings.settings.first() }
    val loaded = initial
    if (loaded == null) LoadingState() else SettingsBody(loaded, updates)
}

@Composable
private fun SettingsBody(loaded: AppSettings, updates: UpdateViewModel) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val s by container.settings.settings.collectAsStateWithLifecycle(loaded)
    val account by container.session.tokens.collectAsStateWithLifecycle()
    val updateState by updates.state.collectAsStateWithLifecycle()
    // Text fields edit local state seeded once from storage, so the cursor never jumps while a save round-trips.
    var blockedWords by remember { mutableStateOf(loaded.blockedWords) }
    var blockedUsers by remember { mutableStateOf(loaded.blockedUsers) }
    var httpHost by remember { mutableStateOf(loaded.adHttpHost) }
    var httpPort by remember { mutableStateOf(loaded.adHttpPort.takeIf { it > 0 }?.toString().orEmpty()) }
    var httpUser by remember { mutableStateOf(loaded.adHttpUser) }
    var httpPassword by remember { mutableStateOf(loaded.adHttpPassword) }
    var newProxy by remember { mutableStateOf("") }
    var proxyError by remember { mutableStateOf<String?>(null) }
    var proxyStatus by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var checkingProxies by remember { mutableStateOf(false) }

    fun update(transform: (AppSettings) -> AppSettings) {
        scope.launch { container.settings.update(transform) }
    }

    val proxies = s.proxyEntries

    fun saveProxies(list: List<ProxyEntry>) = update { it.copy(adProxies = ProxyEntryCodec.encode(list)) }

    fun addProxy() {
        val template = newProxy.trim()
        if (!ProxyUrls.isUsable(template)) {
            proxyError = "That needs to be an https address with \$channel in it, e.g. proxy.example/live/\$channel"
            return
        }
        saveProxies(listOf(ProxyEntry(template)) + proxies.filterNot { it.template == template })
        newProxy = ""
        proxyError = null
    }

    fun checkProxies() {
        if (checkingProxies) return
        checkingProxies = true
        val toCheck = proxies.map { it.template }
        scope.launch {
            try {
                proxyStatus = toCheck.map { t -> async { t to ProxyHealth.responds(container.http, t) } }.awaitAll().toMap()
            } finally {
                checkingProxies = false
            }
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Header("Appearance") }
        item {
            CycleRow("Theme", s.theme, AppTheme.entries, { it.label }) { v -> update { it.copy(theme = v) } }
        }
        item { ToggleRow("Show clock", s.showClock) { v -> update { it.copy(showClock = v) } } }
        item {
            ValueRow("Language row", TwitchLanguages.name(s.language)) {
                update { it.copy(language = TwitchLanguages.next(it.language)) }
            }
        }

        item { Header("Chat") }
        item { ToggleRow("Show chat by default", s.chatVisible) { v -> update { it.copy(chatVisible = v) } } }
        item {
            CycleRow("Chat layout", s.chatLayout, ChatLayout.entries, { it.label }) { v -> update { it.copy(chatLayout = v) } }
        }
        item {
            CycleRow("Chat text size", s.chatTextSize, listOf(0, 1, 2), { SIZE_LABELS[it] }) { v -> update { it.copy(chatTextSize = v) } }
        }
        item {
            CycleRow("Emote size", s.emoteSize, listOf(0, 1, 2), { SIZE_LABELS[it] }) { v -> update { it.copy(emoteSize = v) } }
        }
        item { ToggleRow("Chat timestamps", s.chatTimestamps) { v -> update { it.copy(chatTimestamps = v) } } }
        item {
            CycleRow("Deleted messages", s.deletedStyle, DeletedStyle.entries, { it.label }) { v -> update { it.copy(deletedStyle = v) } }
        }
        item { ToggleRow("Show sub, raid and mod notices", s.showNotices) { v -> update { it.copy(showNotices = v) } } }
        item { ToggleRow("Load recent messages when joining", s.chatHistory) { v -> update { it.copy(chatHistory = v) } } }

        item { Header("Chat filters") }
        item { ToggleRow("Hide known bots", s.hideBots) { v -> update { it.copy(hideBots = v) } } }
        item { ToggleRow("Hide !commands", s.hideCommands) { v -> update { it.copy(hideCommands = v) } } }
        item {
            Label("Blocked words (comma separated)")
            TvTextField(blockedWords, { blockedWords = it; update { st -> st.copy(blockedWords = it) } }, "e.g. spoiler, giveaway")
        }
        item {
            Label("Blocked users (comma separated)")
            TvTextField(blockedUsers, { blockedUsers = it; update { st -> st.copy(blockedUsers = it) } }, "e.g. someusername")
        }

        item { Header("Playback") }
        item {
            CycleRow("Preferred quality", s.quality, QualityPreference.OPTIONS, { QualityPreference.label(it) }) { v ->
                update { it.copy(quality = v) }
            }
        }
        item { ToggleRow("Auto-refresh stalled streams", s.autoRefresh) { v -> update { it.copy(autoRefresh = v) } } }
        item {
            CycleRow("Auto-refresh mode", s.refreshMode, RefreshMode.entries, { it.label }) { v -> update { it.copy(refreshMode = v) } }
        }
        item {
            CycleRow("Auto-refresh retries", s.refreshAttempts, listOf(1, 3, 5, 10, 20), { "$it" }) { v -> update { it.copy(refreshAttempts = v) } }
        }
        item {
            CycleRow("Auto-refresh interval", s.refreshIntervalSeconds, listOf(2, 5, 10, 20, 30), { "$it s" }) { v ->
                update { it.copy(refreshIntervalSeconds = v) }
            }
        }
        item { ToggleRow("Keep screen on while watching", s.keepScreenOn) { v -> update { it.copy(keepScreenOn = v) } } }
        item { ToggleRow("Touch and swipe gestures", s.gestures) { v -> update { it.copy(gestures = v) } } }
        item { ToggleRow("Follow raids automatically", s.followRaids) { v -> update { it.copy(followRaids = v) } } }
        item { ToggleRow("Picture-in-picture on Home button", s.pipOnHome) { v -> update { it.copy(pipOnHome = v) } } }
        item {
            ToggleRow("Live previews on cards (needs a capable device)", s.livePreviews) { v -> update { it.copy(livePreviews = v) } }
        }

        item { Header("Ads") }
        item {
            CycleRow("Ad handling", s.adMode, AdMode.entries, { it.label }) { v -> update { it.copy(adMode = v) } }
        }
        item {
            Text(adBlurb(s.adMode), style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
        }
        if (s.adMode != AdMode.OFF) {
            item {
                CycleRow("Playback token player type", s.tokenPlayerType, PlayerTypes.ALL, { it }) { v ->
                    update { it.copy(tokenPlayerType = v) }
                }
            }
        }
        when (s.adMode) {
            AdMode.PROXY -> {
                items(proxies, key = { it.template }) { entry ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            val status = proxyStatus[entry.template]?.let { if (it) " · online" else " · offline" }.orEmpty()
                            ValueRow(entry.template.substringAfter("://").substringBefore('/'), (if (entry.enabled) "On" else "Off") + status) {
                                saveProxies(proxies.map { if (it.template == entry.template) it.copy(enabled = !it.enabled) else it })
                            }
                        }
                        PillButton("Remove", { saveProxies(proxies.filterNot { it.template == entry.template }) })
                    }
                }
                item {
                    Label("Add a proxy address (https, with \$channel in it). Entries are tried top to bottom.")
                    TvTextField(newProxy, { newProxy = it; proxyError = null }, "e.g. proxy.example/live/\$channel", onSubmit = ::addProxy)
                }
                proxyError?.let { message ->
                    item { Text(message, style = MaterialTheme.typography.bodySmall, color = Palette.BrandSoft) }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PillButton("Add", ::addProxy)
                        PillButton(if (checkingProxies) "Checking…" else "Check which are online", ::checkProxies)
                        PillButton("Reset to Xtra's list", { saveProxies(ProxyDefaults.customProxies) })
                    }
                }
            }
            AdMode.HTTP_PROXY -> {
                item {
                    Label("Proxy host")
                    TvTextField(httpHost, { httpHost = it; update { st -> st.copy(adHttpHost = it.trim()) } }, "e.g. proxy.example.com")
                }
                item {
                    Label("Port")
                    TvTextField(
                        httpPort,
                        { raw ->
                            val digits = raw.filter(Char::isDigit).take(5)
                            httpPort = digits
                            update { st -> st.copy(adHttpPort = digits.toIntOrNull() ?: 0) }
                        },
                        "e.g. 8080",
                    )
                }
                item {
                    Label("Username (optional)")
                    TvTextField(httpUser, { httpUser = it; update { st -> st.copy(adHttpUser = it) } }, "leave empty if none")
                }
                item {
                    Label("Password (optional; stored on this device only)")
                    TvTextField(httpPassword, { httpPassword = it; update { st -> st.copy(adHttpPassword = it) } }, "leave empty if none")
                }
                item { ToggleRow("Send playback-token requests through the proxy", s.adHttpToken) { v -> update { it.copy(adHttpToken = v) } } }
                item { ToggleRow("Send the master-playlist request through the proxy", s.adHttpMultivariant) { v -> update { it.copy(adHttpMultivariant = v) } } }
                item { ToggleRow("Send media-playlist requests through the proxy", s.adHttpMedia) { v -> update { it.copy(adHttpMedia = v) } } }
                item {
                    ValueRow("Reset to Xtra's default proxy", "${ProxyDefaults.httpProxy.host}:${ProxyDefaults.httpProxy.port}") {
                        val d = ProxyDefaults.httpProxy
                        httpHost = d.host
                        httpPort = d.port.toString()
                        httpUser = ""
                        httpPassword = ""
                        update {
                            it.copy(
                                adHttpHost = d.host, adHttpPort = d.port, adHttpUser = "", adHttpPassword = "",
                                adHttpToken = d.proxyToken, adHttpMultivariant = d.proxyMultivariant, adHttpMedia = d.proxyMedia,
                            )
                        }
                    }
                }
            }
            AdMode.VIDEO_SWAP -> {
                val swaps = s.swapEntries
                items(swaps, key = { "${it.platform}/${it.playerType}" }) { entry ->
                    ValueRow("${entry.platform} · ${entry.playerType}", if (entry.enabled) "On" else "Off") {
                        val updated = swaps.map { if (it == entry) it.copy(enabled = !it.enabled) else it }
                        update { it.copy(adSwap = ProxyEntryCodec.encodeSwap(updated)) }
                    }
                }
                item {
                    ValueRow("Reset to Xtra's list", "") {
                        update { it.copy(adSwap = ProxyEntryCodec.encodeSwap(ProxyDefaults.videoSwap)) }
                    }
                }
            }
            AdMode.OFF, AdMode.HIDE -> Unit
        }

        item { Header("Notifications and home screen") }
        item {
            ToggleRow("Notify when a followed streamer goes live", s.goLiveNotifications) { v ->
                update { it.copy(goLiveNotifications = v) }
            }
        }
        item {
            ToggleRow("Add a \"Live now\" row to the Android TV home screen", s.homeChannel) { v ->
                update { it.copy(homeChannel = v) }
            }
        }
        item {
            Text(
                "Checked about every 15 minutes while the TV is on. The home-screen row only appears on launchers that " +
                    "support app channels; Google TV's newer home screen may ignore it.",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Muted,
            )
        }

        item { Header("Updates") }
        item { ToggleRow("Check for updates on launch", s.autoUpdateCheck) { v -> update { it.copy(autoUpdateCheck = v) } } }
        item {
            ValueRow("Check for updates now", updateState.statusLabel()) {
                when (val st = updateState) {
                    is UpdateUi.Available -> updates.install(st.info)
                    else -> updates.check(manual = true)
                }
            }
        }

        item { Header("Remote shortcuts (in the player)") }
        item {
            Text(
                "OK / Up: controls · Down: peek at other live channels · Left / Right: hide / show chat (live) or seek ±10 s (VOD)\n" +
                    "Red: chat · Green: quality · Yellow: audio-only · Blue: peek · Channel +/-: next / previous followed channel\n" +
                    "Play/Pause, Rewind, Fast-forward · Info or Menu: controls · Back: close menu, then controls, then leave",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.Muted,
            )
        }

        item { Header("Account") }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Text(
                    "Signed in as ${account?.login ?: "—"}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Palette.OnBackground,
                )
                PillButton("Sign out", { scope.launch { container.session.signOut() } })
            }
        }

        item { Header("About") }
        item {
            Text(
                "S0undPlus ${BuildConfig.VERSION_NAME}. An unofficial, independent Twitch viewer for Google TV. " +
                    "Not affiliated with or endorsed by Twitch Interactive, Inc. Chat is read-only and anonymous. " +
                    "Twitch doesn't allow third-party apps to follow or unfollow channels, so use twitch.tv or the " +
                    "official app for that (the channel page has a QR code).",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.Muted,
            )
        }
    }
}

/**
 * What each ad mode does and costs, in plain words. The proxy modes send viewing activity to someone else's server and
 * go against Twitch's terms, so that is said right where the switch is.
 */
private fun adBlurb(mode: AdMode): String = when (mode) {
    AdMode.OFF -> "Nothing about ads is changed, and nothing is sent to anyone but Twitch."
    AdMode.HIDE ->
        "Mutes the sound and covers the picture while an ad plays, then returns to the stream. You still wait the ad " +
            "out. Nothing is sent to anyone but Twitch. It works by reading Twitch's own ad markers in the stream; " +
            "if Twitch changes them it quietly stops working."
    AdMode.PROXY ->
        "Plays the stream from a proxy service on the list below instead of straight from Twitch, moving to the next " +
            "one when one fails and to Twitch when all do. These are third-party services (the starting list is copied " +
            "from Xtra's), not run by this app: the one in use sees your IP address and which channel you watch, and " +
            "any of them can stop working or change at any time. It goes against Twitch's terms and costs the " +
            "streamer ad revenue; Twitch Turbo, or subscribing to the channel, removes ads without any of that."
    AdMode.HTTP_PROXY ->
        "Watches the normal stream for ads. When one starts, the stream's playlists are fetched through your HTTP " +
            "proxy for as long as it lasts. The proxy sees your IP address and what you watch, and the same caveats " +
            "about Twitch's terms and streamer revenue apply. Falls back to hiding the ad if the proxy fails."
    AdMode.VIDEO_SWAP ->
        "Watches the normal stream for ads. When one starts, switches to a copy requested with a different player " +
            "identity (the way Twitch's embedded and Android players ask). Only Twitch is contacted, but whether it " +
            "serves ads to those identities changes over time, and it may go against Twitch's terms. Falls back to " +
            "hiding the ad."
}

@Composable
private fun Header(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, color = Palette.BrandSoft, modifier = Modifier.padding(top = 16.dp))
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Palette.Muted, modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
private fun ToggleRow(title: String, value: Boolean, onChange: (Boolean) -> Unit) {
    ValueRow(title, if (value) "On" else "Off") { onChange(!value) }
}

/** A row that steps through [options] each time it is clicked. */
@Composable
private fun <T> CycleRow(title: String, current: T, options: List<T>, label: (T) -> String, onChange: (T) -> Unit) {
    ValueRow(title, label(current)) {
        val next = options[(options.indexOf(current) + 1).mod(options.size)]
        onChange(next)
    }
}

@Composable
private fun ValueRow(title: String, value: String, onClick: () -> Unit) {
    FocusSurface(onClick, Modifier.fillMaxWidth(), focusedScale = 1.02f) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Palette.OnBackground)
            Text(value, style = MaterialTheme.typography.titleSmall, color = Palette.BrandSoft)
        }
    }
}
