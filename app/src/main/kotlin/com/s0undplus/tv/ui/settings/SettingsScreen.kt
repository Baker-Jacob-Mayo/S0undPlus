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
import com.s0undplus.tv.data.ChatLayout
import com.s0undplus.tv.ui.components.FocusSurface
import com.s0undplus.tv.ui.components.LoadingState
import com.s0undplus.tv.ui.components.PillButton
import com.s0undplus.tv.ui.components.TvTextField
import com.s0undplus.tv.ui.theme.Palette
import com.s0undplus.twitch.playback.QualityPreference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val TEXT_SIZES = listOf("Small", "Medium", "Large")

@Composable
fun SettingsContent() {
    val container = LocalAppContainer.current
    val initial by produceState<AppSettings?>(null) { value = container.settings.settings.first() }
    val loaded = initial
    if (loaded == null) LoadingState() else SettingsBody(loaded)
}

@Composable
private fun SettingsBody(loaded: AppSettings) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsStateWithLifecycle(loaded)
    val account by container.session.tokens.collectAsStateWithLifecycle()
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
        item { Header("Chat") }
        item {
            ValueRow("Show chat by default", if (settings.chatVisible) "On" else "Off") {
                update { it.copy(chatVisible = !it.chatVisible) }
            }
        }
        item {
            ValueRow("Chat layout", settings.chatLayout.label) {
                update { it.copy(chatLayout = ChatLayout.entries[(it.chatLayout.ordinal + 1) % ChatLayout.entries.size]) }
            }
        }
        item {
            ValueRow("Chat text size", TEXT_SIZES[settings.chatTextSize]) {
                update { it.copy(chatTextSize = (it.chatTextSize + 1) % TEXT_SIZES.size) }
            }
        }
        item {
            ValueRow("Load recent messages when joining", if (settings.chatHistory) "On" else "Off") {
                update { it.copy(chatHistory = !it.chatHistory) }
            }
        }

        item { Header("Chat filters") }
        item {
            ValueRow("Hide known bots", if (settings.hideBots) "On" else "Off") {
                update { it.copy(hideBots = !it.hideBots) }
            }
        }
        item {
            ValueRow("Hide !commands", if (settings.hideCommands) "On" else "Off") {
                update { it.copy(hideCommands = !it.hideCommands) }
            }
        }
        item {
            Label("Blocked words (comma separated)")
            TvTextField(blockedWords, { blockedWords = it; update { s -> s.copy(blockedWords = it) } }, "e.g. spoiler, giveaway")
        }
        item {
            Label("Blocked users (comma separated)")
            TvTextField(blockedUsers, { blockedUsers = it; update { s -> s.copy(blockedUsers = it) } }, "e.g. someusername")
        }

        item { Header("Playback") }
        item {
            ValueRow("Preferred quality", QualityPreference.label(settings.quality)) {
                val options = QualityPreference.OPTIONS
                val next = options[(options.indexOf(settings.quality) + 1) % options.size]
                update { it.copy(quality = next) }
            }
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
                    "Not affiliated with or endorsed by Twitch Interactive, Inc. Chat is read-only and anonymous.",
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
