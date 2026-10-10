package com.s0undplus.tv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import com.s0undplus.twitch.playback.QualityPreference
import com.s0undplus.twitch.playback.RefreshMode
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

    fun update(transform: (AppSettings) -> AppSettings) {
        scope.launch { container.settings.update(transform) }
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
