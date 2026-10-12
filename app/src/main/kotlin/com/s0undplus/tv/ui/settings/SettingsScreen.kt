package com.s0undplus.tv.ui.settings

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import com.s0undplus.tv.ui.components.KeylineScrolling
import com.s0undplus.tv.ui.components.LanguageDialog
import com.s0undplus.tv.ui.components.ListRowSurface
import com.s0undplus.tv.ui.theme.AppType
import com.s0undplus.tv.ui.theme.Layout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
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
import com.s0undplus.tv.data.OtherChannels
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val SIZE_LABELS = listOf("Small", "Medium", "Large")

/** The sections, in list order: the left pane lists them and marks the one being browsed. */
private val SECTIONS = listOf(
    "Appearance", "Chat", "Chat filters", "Playback", "Ads", "Notifications and home screen", "Updates",
    "Remote shortcuts (in the player)", "Account", "About",
)

/** What the left pane says about the focused row. */
private class PaneState {
    var title by mutableStateOf("")
    var help by mutableStateOf<String?>(null)
}

private val LocalPane = staticCompositionLocalOf { PaneState() }

/**
 * Settings, in two panes: on the left the list of sections (the one being browsed is marked) and help for the
 * focused row; on the right one continuous list of every setting. [entry] goes on the first row, and that row takes
 * focus when [focusNow] says so (the page was just opened from the navigation).
 */
@Composable
fun SettingsContent(updates: UpdateViewModel, entry: FocusRequester, focusNow: () -> Boolean) {
    val container = LocalAppContainer.current
    val initial by produceState<AppSettings?>(null) { value = container.settings.settings.first() }
    val loaded = initial
    if (loaded == null) LoadingState() else SettingsBody(loaded, updates, entry, focusNow)
}

@Composable
private fun SettingsBody(loaded: AppSettings, updates: UpdateViewModel, entry: FocusRequester, focusNow: () -> Boolean) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val s by container.settings.settings.collectAsStateWithLifecycle(loaded)
    val account by container.session.tokens.collectAsStateWithLifecycle()
    val updateState by updates.state.collectAsStateWithLifecycle()
    // Text fields edit local state seeded from storage (see SavedTextField), so the cursor never jumps while a save
    // round-trips. [seed] is what they are seeded from; "Reset" refreshes it and rebuilds the fields.
    var seed by remember { mutableStateOf(loaded) }
    var resetCount by remember { mutableIntStateOf(0) }
    var showLanguages by remember { mutableStateOf(false) }
    var newProxy by remember { mutableStateOf("") }
    var proxyError by remember { mutableStateOf<String?>(null) }
    var proxyStatus by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var checkingProxies by remember { mutableStateOf(false) }

    // On the app's scope, not the screen's: a save that is still pending when the screen closes must not be lost.
    fun update(transform: (AppSettings) -> AppSettings) {
        container.appScope.launch { container.settings.update(transform) }
    }

    val proxies = s.proxyEntries

    // Edits are applied to the stored list inside the update, so two quick presses can't undo each other.
    fun editProxies(change: (List<ProxyEntry>) -> List<ProxyEntry>) =
        update { cur -> cur.copy(adProxies = ProxyEntryCodec.encode(change(ProxyEntryCodec.decode(cur.adProxies)))) }

    fun editSwaps(change: (List<VideoSwapEntry>) -> List<VideoSwapEntry>) =
        update { cur -> cur.copy(adSwap = ProxyEntryCodec.encodeSwap(change(ProxyEntryCodec.decodeSwap(cur.adSwap)))) }

    fun addProxy() {
        // Pasted text may hold several addresses; each usable one is added (and the unusable ones are reported).
        val pasted = newProxy.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val usable = ProxyUrls.parseList(newProxy)
        if (usable.isEmpty()) {
            proxyError = "That needs to be an https address with \$channel in it, e.g. proxy.example/live/\$channel"
            return
        }
        editProxies { cur -> usable.map { ProxyEntry(it) } + cur.filterNot { it.template in usable } }
        newProxy = ""
        proxyError = if (pasted.size > usable.size) "Added ${usable.size}; skipped ${pasted.size - usable.size} that weren't usable." else null
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

    val pane = remember { PaneState() }
    val languageRow = remember { FocusRequester() }
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        if (focusNow()) {
            withFrameNanos { }
            try {
                entry.requestFocus()
            } catch (_: IllegalStateException) {
                // not attached yet; Right from the navigation still reaches the list
            }
        }
    }
    // The section being browsed: the last heading at or above where the focused row rests. When no heading is on
    // screen (a long section), it stays what it was.
    var section by remember { mutableStateOf(SECTIONS.first()) }
    LaunchedEffect(listState) {
        snapshotFlow {
            val headers = listState.layoutInfo.visibleItemsInfo.mapNotNull { info ->
                val key = info.key as? String ?: return@mapNotNull null
                if (key.startsWith("h:")) key.removePrefix("h:") to info.offset else null
            }
            val restPx = listState.layoutInfo.viewportStartOffset + 200
            headers.lastOrNull { it.second <= restPx }?.first
                ?: headers.firstOrNull()?.first?.let { first -> SECTIONS.getOrNull(SECTIONS.indexOf(first) - 1) ?: first }
        }.collect { found -> if (found != null) section = found }
    }

    Row(Modifier.fillMaxSize()) {
        SettingsPane(section, pane)
        CompositionLocalProvider(LocalPane provides pane) {
          KeylineScrolling(SETTINGS_KEYLINE) {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(start = 24.dp, end = Layout.endMargin, top = 28.dp, bottom = 260.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
            item(key = "h:Appearance") { Header("Appearance") }
            item {
                CycleRow("Theme", s.theme, AppTheme.entries, { it.label }, Modifier.focusRequester(entry)) { v -> update { it.copy(theme = v) } }
            }
            item { ToggleRow("Show clock", s.showClock) { v -> update { it.copy(showClock = v) } } }
            item {
                ValueRow(
                    "Language row",
                    TwitchLanguages.name(s.language),
                    Modifier.focusRequester(languageRow),
                    help = "The language of the streams on the Language page.",
                ) {
                    showLanguages = true
                }
            }

            item(key = "h:Chat") { Header("Chat") }
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

            item(key = "h:Chat filters") { Header("Chat filters") }
            item { ToggleRow("Hide known bots", s.hideBots) { v -> update { it.copy(hideBots = v) } } }
            item { ToggleRow("Hide !commands", s.hideCommands) { v -> update { it.copy(hideCommands = v) } } }
            item {
                Label("Blocked words (comma separated)")
                SavedTextField(loaded.blockedWords, "e.g. spoiler, giveaway", onSave = { v -> update { it.copy(blockedWords = v) } })
            }
            item {
                Label("Blocked users (comma separated)")
                SavedTextField(loaded.blockedUsers, "e.g. someusername", onSave = { v -> update { it.copy(blockedUsers = v) } })
            }

            item(key = "h:Playback") { Header("Playback") }
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
                ToggleRow("Zoom video to fill the screen", s.zoomVideo, help = "Crops 4:3 and letterboxed streams so they fill the screen.") { v ->
                    update { it.copy(zoomVideo = v) }
                }
            }
            item {
                ToggleRow(
                    "Match the stream's frame rate",
                    s.matchFrameRate,
                    help = "Switches the TV to a refresh rate that suits the stream, such as 50 Hz for a 50 fps stream, which " +
                        "otherwise judders on a 60 Hz picture. The screen goes dark for a moment when it switches.",
                ) { v ->
                    update { it.copy(matchFrameRate = v) }
                }
            }
            item {
                CycleRow(
                    "Other channels to switch to",
                    s.otherChannels,
                    OtherChannels.entries,
                    { it.label },
                    help = "The channels the player's peek view (Blue button) and Channel +/- step through.",
                ) { v ->
                    update { it.copy(otherChannels = v) }
                }
            }
            item {
                ToggleRow(
                    "Live previews on cards",
                    s.livePreviews,
                    help = "Plays the focused live channel, muted, inside its card after a moment. Needs a capable device.",
                ) { v -> update { it.copy(livePreviews = v) } }
            }

            item(key = "h:Ads") { Header("Ads") }
            item {
                CycleRow("Ad handling", s.adMode, AdMode.entries, { it.label }, help = adBlurb(s.adMode)) { v -> update { it.copy(adMode = v) } }
            }
            item {
                Text(adBlurb(s.adMode), style = AppType.BodyM, color = Palette.TextTertiary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
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
                                    editProxies { cur -> cur.map { if (it.template == entry.template) it.copy(enabled = !it.enabled) else it } }
                                }
                            }
                            PillButton("Remove", { editProxies { cur -> cur.filterNot { it.template == entry.template } } })
                        }
                    }
                    // Keyed: they follow a list that grows and shrinks, and by position they would be a different item
                    // (and lose their state and focus) every time a proxy is added or removed.
                    item(key = "add-proxy-field") {
                        Label("Add a proxy address (https, with \$channel in it). Entries are tried top to bottom.")
                        TvTextField(newProxy, { newProxy = it; proxyError = null }, "e.g. proxy.example/live/\$channel", onSubmit = ::addProxy)
                    }
                    proxyError?.let { message ->
                        item(key = "add-proxy-error") { Text(message, style = AppType.BodyM, color = Palette.BrandSoft) }
                    }
                    item(key = "add-proxy-buttons") {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            PillButton("Add", ::addProxy)
                            PillButton(if (checkingProxies) "Checking…" else "Check which are online", ::checkProxies)
                            PillButton("Reset to Xtra's list", { editProxies { ProxyDefaults.customProxies } })
                        }
                    }
                }
                AdMode.HTTP_PROXY -> {
                    item {
                        Label("Proxy host")
                        key(resetCount) {
                            SavedTextField(seed.adHttpHost, "e.g. proxy.example.com", onSave = { v -> update { it.copy(adHttpHost = v.trim()) } })
                        }
                    }
                    item {
                        Label("Port (1–65535)")
                        key(resetCount) {
                            SavedTextField(
                                initial = seed.adHttpPort.takeIf { it > 0 }?.toString().orEmpty(),
                                hint = "e.g. 8080",
                                filter = { raw -> raw.filter(Char::isDigit).take(5) },
                                onSave = { v ->
                                    // Out of range counts as "not set": the hint below says so instead of a clamped port being used.
                                    update { it.copy(adHttpPort = v.toIntOrNull()?.takeIf { port -> port in 1..65535 } ?: 0) }
                                },
                            )
                        }
                    }
                    if (s.httpProxy == null) {
                        item {
                            Text(
                                "Enter a host name or address without http:// or a path, and a port from 1 to 65535. Until then this mode only hides ads.",
                                style = AppType.BodyM,
                                color = Palette.BrandSoft,
                            )
                        }
                    }
                    item {
                        Label("Username (optional)")
                        key(resetCount) {
                            SavedTextField(seed.adHttpUser, "leave empty if none", onSave = { v -> update { it.copy(adHttpUser = v) } })
                        }
                    }
                    item {
                        Label("Password (optional; stored on this device only)")
                        key(resetCount) {
                            SavedTextField(seed.adHttpPassword, "leave empty if none", onSave = { v -> update { it.copy(adHttpPassword = v) } })
                        }
                    }
                    item { ToggleRow("Send playback-token requests through the proxy", s.adHttpToken) { v -> update { it.copy(adHttpToken = v) } } }
                    item { ToggleRow("Send the master-playlist request through the proxy", s.adHttpMultivariant) { v -> update { it.copy(adHttpMultivariant = v) } } }
                    item { ToggleRow("Send media-playlist requests through the proxy", s.adHttpMedia) { v -> update { it.copy(adHttpMedia = v) } } }
                    item {
                        ValueRow("Reset to Xtra's default proxy", "${ProxyDefaults.httpProxy.host}:${ProxyDefaults.httpProxy.port}") {
                            val d = ProxyDefaults.httpProxy
                            container.appScope.launch {
                                container.settings.update {
                                    it.copy(
                                        adHttpHost = d.host, adHttpPort = d.port, adHttpUser = "", adHttpPassword = "",
                                        adHttpToken = d.proxyToken, adHttpMultivariant = d.proxyMultivariant, adHttpMedia = d.proxyMedia,
                                    )
                                }
                                seed = container.settings.settings.first()
                                resetCount++ // rebuild the fields from the stored values
                            }
                        }
                    }
                }
                AdMode.VIDEO_SWAP -> {
                    val swaps = s.swapEntries
                    items(swaps, key = { "${it.platform}/${it.playerType}" }) { entry ->
                        ValueRow("${entry.platform} · ${entry.playerType}", if (entry.enabled) "On" else "Off") {
                            editSwaps { cur ->
                                cur.map {
                                    if (it.platform == entry.platform && it.playerType == entry.playerType) it.copy(enabled = !it.enabled) else it
                                }
                            }
                        }
                    }
                    item {
                        ValueRow("Reset to Xtra's list", "") {
                            editSwaps { ProxyDefaults.videoSwap }
                        }
                    }
                }
                AdMode.OFF, AdMode.HIDE -> Unit
            }

            item(key = "h:Notifications and home screen") { Header("Notifications and home screen") }
            item {
                ToggleRow("Notify when a followed streamer goes live", s.goLiveNotifications, help = NOTIFY_HELP) { v ->
                    update { it.copy(goLiveNotifications = v) }
                }
            }
            item {
                ToggleRow("Add a \"Live now\" row to the Android TV home screen", s.homeChannel, help = NOTIFY_HELP) { v ->
                    update { it.copy(homeChannel = v) }
                }
            }

            item(key = "h:Updates") { Header("Updates") }
            item { ToggleRow("Check for updates on launch", s.autoUpdateCheck) { v -> update { it.copy(autoUpdateCheck = v) } } }
            item {
                ValueRow("Check for updates now", updateState.statusLabel()) {
                    when (val st = updateState) {
                        is UpdateUi.Available -> updates.install(st.info)
                        else -> updates.check(manual = true)
                    }
                }
            }

            item(key = "h:Remote shortcuts (in the player)") { Header("Remote shortcuts (in the player)") }
            item {
                Text(
                    "OK / Up / Down: show the controls (Play/Pause first) · Left / Right: hide / show chat (live) or skip ±10 s (videos)\n" +
                        "In the controls: Down closes them · Up goes to the seek bar on videos (Left / Right to scrub, OK to jump), and Up again closes them\n" +
                        "Red: chat · Green: quality · Yellow: audio-only · Blue: other live channels · Channel +/-: next / previous channel\n" +
                        "Play/Pause, Rewind, Fast-forward · Info or Menu: controls · Back: close menu, then controls, then leave",
                    style = AppType.BodyM,
                    color = Palette.Muted,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }

            item(key = "h:Account") { Header("Account") }
            item {
                Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Text(
                        "Signed in as ${account?.login ?: "—"}",
                        style = AppType.BodyL,
                        color = Palette.OnBackground,
                    )
                    PillButton("Sign out", { scope.launch { container.session.signOut() } })
                }
            }

            item(key = "h:About") { Header("About") }
            item {
                Text(
                    "S0undPlus ${BuildConfig.VERSION_NAME}. An unofficial, independent Twitch viewer for Google TV. " +
                        "Not affiliated with or endorsed by Twitch Interactive, Inc. Chat is read-only and anonymous. " +
                        "Twitch doesn't allow third-party apps to follow or unfollow channels, so use twitch.tv or the " +
                        "official app for that (the channel page has a QR code).",
                    style = AppType.BodyM,
                    color = Palette.Muted,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            }
          }
        }
    }
    Box {
        if (showLanguages) {
            LanguageDialog(
                current = s.language,
                onPick = { code ->
                    showLanguages = false
                    update { it.copy(language = code) }
                },
                onDismiss = { showLanguages = false },
                returnFocus = languageRow,
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

/**
 * A text field that edits local state and saves it half a second after typing stops, when it loses focus, or when
 * the screen closes, instead of rewriting every setting to disk on each keystroke typed with a TV remote.
 */
@Composable
private fun SavedTextField(
    initial: String,
    hint: String,
    onSave: (String) -> Unit,
    filter: (String) -> String = { it },
) {
    // Saveable, because a LazyColumn row is disposed when it scrolls off-screen: plain `remember` would rebuild the field
    // from the (older) value it was first given and the next keystroke would overwrite what was just saved.
    var text by rememberSaveable { mutableStateOf(initial) }
    var saved by rememberSaveable { mutableStateOf(initial) }
    val latestSave by rememberUpdatedState(onSave)

    fun commit() {
        if (text != saved) {
            saved = text
            latestSave(text)
        }
    }

    LaunchedEffect(text) {
        if (text != saved) {
            delay(SAVE_DELAY_MS)
            commit()
        }
    }
    DisposableEffect(Unit) { onDispose { commit() } }
    TvTextField(text, { text = filter(it) }, hint, Modifier.onFocusChanged { if (!it.hasFocus) commit() })
}

private const val SAVE_DELAY_MS = 500L

@Composable
private fun Header(text: String) {
    Text(text, style = AppType.RowTitle, color = Palette.BrandSoft, modifier = Modifier.padding(start = 20.dp, top = 24.dp, bottom = 6.dp))
}

@Composable
private fun Label(text: String) {
    Text(text, style = AppType.BodyM, color = Palette.TextTertiary, modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 6.dp))
}

@Composable
private fun ToggleRow(title: String, value: Boolean, modifier: Modifier = Modifier, help: String? = null, onChange: (Boolean) -> Unit) {
    ValueRow(title, if (value) "On" else "Off", modifier, help, valueColor = if (value) Palette.BrandSoft else Palette.TextTertiary) {
        onChange(!value)
    }
}

/** A row that steps through [options] each time it is clicked. */
@Composable
private fun <T> CycleRow(
    title: String,
    current: T,
    options: List<T>,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    help: String? = null,
    onChange: (T) -> Unit,
) {
    ValueRow(title, label(current), modifier, help) {
        val next = options[(options.indexOf(current) + 1).mod(options.size)]
        onChange(next)
    }
}

/** A setting: its name on the left, its value on the right. Focused, it turns white and the left pane explains it. */
@Composable
private fun ValueRow(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    help: String? = null,
    valueColor: Color = Palette.BrandSoft,
    onClick: () -> Unit,
) {
    val pane = LocalPane.current
    ListRowSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
        onFocused = {
            pane.title = title
            pane.help = help
        },
    ) { focused ->
        Text(
            title,
            style = AppType.Button,
            color = if (focused) Palette.OnFocusFill else Palette.OnBackground,
            modifier = Modifier.weight(1f).padding(start = 20.dp, top = 14.dp, bottom = 14.dp, end = 16.dp),
        )
        Text(
            value,
            style = AppType.BodyM,
            color = if (focused) Palette.OnFocusFillSecondary else valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 220.dp).padding(end = 20.dp),
        )
    }
}

/**
 * The left pane: the page title, the sections (the one being browsed is marked) and what the focused row does.
 * Purely a guide, so it is neither focusable nor part of what accessibility services and tests read (the list holds
 * every one of these words already).
 */
@Composable
private fun SettingsPane(section: String, pane: PaneState) {
    Column(
        Modifier
            .padding(start = Layout.keyline, top = 32.dp)
            .width(240.dp)
            .fillMaxHeight()
            .clearAndSetSemantics { },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Settings", style = AppType.Headline, color = Palette.OnBackground, modifier = Modifier.padding(bottom = 12.dp))
        for (name in SECTIONS) {
            val current = name == section
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(3.dp).height(16.dp).background(if (current) Palette.Brand else Color.Transparent))
                Text(
                    name.substringBefore(" (in"),
                    style = AppType.BodyM,
                    color = if (current) Palette.OnBackground else Palette.TextTertiary,
                    modifier = Modifier.padding(start = 10.dp),
                    maxLines = 1,
                )
            }
        }
        val help = pane.help
        if (help != null) {
            Text(pane.title, style = AppType.CardTitle, color = Palette.OnBackground, modifier = Modifier.padding(top = 20.dp))
            Text(help, style = AppType.CardMeta, color = Palette.Muted)
        }
    }
}

private val SETTINGS_KEYLINE = 150.dp

private const val NOTIFY_HELP = "Checked about every 15 minutes while the TV is on. The home-screen row only appears on " +
    "launchers that support app channels; Google TV's newer home screen may ignore it."
