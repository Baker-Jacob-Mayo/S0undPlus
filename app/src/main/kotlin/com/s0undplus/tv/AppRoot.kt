package com.s0undplus.tv

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.s0undplus.tv.ui.player.FrameRateSwitcher
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.s0undplus.tv.ui.channel.CategoryScreen
import com.s0undplus.tv.ui.channel.ChannelScreen
import com.s0undplus.tv.ui.components.LoadingState
import com.s0undplus.tv.ui.home.HomeScreen
import com.s0undplus.tv.ui.home.HomeViewModel
import com.s0undplus.tv.ui.login.LoginScreen
import com.s0undplus.tv.ui.nav.NavViewModel
import com.s0undplus.tv.ui.nav.PlayTarget
import com.s0undplus.tv.ui.nav.Route
import com.s0undplus.tv.ui.nav.toPlayTarget
import com.s0undplus.tv.ui.player.PlayerScreen
import com.s0undplus.tv.ui.theme.Palette
import com.s0undplus.tv.ui.update.UpdateViewModel
import com.s0undplus.twitch.DeepLink
import kotlin.coroutines.cancellation.CancellationException

@Composable
fun AppRoot() {
    val container = LocalAppContainer.current
    var restored by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        container.session.restore()
        restored = true
    }
    val tokens by container.session.tokens.collectAsStateWithLifecycle()

    val current = tokens
    when {
        BuildConfig.TWITCH_CLIENT_ID.isBlank() -> ConfigMissingScreen()
        !restored -> LoadingState()
        current == null -> LoginScreen()
        else -> SignedInHost(userId = current.userId, login = current.login)
    }
}

@Composable
private fun SignedInHost(userId: String, login: String) {
    val container = LocalAppContainer.current
    val bridge = LocalActivityBridge.current
    val appContext = LocalContext.current.applicationContext
    // Keyed by account so signing out and in as someone else never shows the previous user's data.
    val nav: NavViewModel = viewModel(key = "nav-$userId")
    val home = viewModel(key = "home-$userId") { HomeViewModel(container) }
    val updates = viewModel(key = "updates-$userId") { UpdateViewModel(appContext, container) }

    LaunchedEffect(Unit) { updates.checkOnLaunch() }

    // Links (notification taps, TV home row, twitch.tv URLs) wait here until the viewer is signed in and Home exists.
    val link = bridge.pendingLink
    LaunchedEffect(link) {
        val l = link ?: return@LaunchedEffect
        bridge.pendingLink = null
        when (l) {
            is DeepLink.Live -> nav.push(Route.Play(PlayTarget.Live(l.login, l.login, null)))
            is DeepLink.Vod -> {
                val video = try {
                    container.helix.videoById(l.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                if (video != null) nav.push(Route.Play(video.toPlayTarget()))
            }
            DeepLink.Home -> while (nav.canPop) nav.pop()
        }
    }

    BackHandler(enabled = nav.canPop) { nav.pop() }

    // One switcher for the whole session, so going from one stream to the next (Channel +/-, peek, raids, a reconnect)
    // keeps a suitable refresh rate instead of dropping to the default and switching back. Reset only on leaving playback.
    val activity = LocalActivity.current
    val frameRate = remember(activity) { activity?.let(::FrameRateSwitcher) }
    val inPlayer = nav.current is Route.Play
    LaunchedEffect(inPlayer) { if (!inPlayer) frameRate?.reset() }
    DisposableEffect(frameRate) { onDispose { frameRate?.reset() } }

    when (val route = nav.current) {
        Route.Home -> HomeScreen(
            vm = home,
            updates = updates,
            onOpenStream = { nav.push(Route.Play(it.toPlayTarget())) },
            onOpenCategory = { nav.push(Route.Category(it.id, it.name)) },
            onOpenChannel = { nav.push(Route.Channel(it)) },
            onPlay = { nav.push(Route.Play(it)) },
        )
        is Route.Category -> EntryScope(nav) { CategoryScreen(route.id, route.name) { nav.push(Route.Play(it.toPlayTarget())) } }
        is Route.Channel -> EntryScope(nav) { ChannelScreen(route.login) { nav.push(Route.Play(it)) } }
        // Keyed by target so switching channel (peek, channel +/-, raids) builds a fresh player, chat and sockets.
        is Route.Play -> key(route.target) {
            PlayerScreen(
                target = route.target,
                onExit = nav::pop,
                onOpenChannel = { nav.push(Route.Channel(it)) },
                onSwitch = { nav.replaceTop(Route.Play(it)) },
                frameRate = frameRate,
            )
        }
    }
}

/** Gives the screen of the top back-stack entry that entry's own ViewModels (see [NavViewModel.storeFor]). */
@Composable
private fun EntryScope(nav: NavViewModel, content: @Composable () -> Unit) {
    val entryId = nav.currentEntryId
    val owner = remember(entryId) {
        object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = nav.storeFor(entryId)
        }
    }
    key(entryId) {
        CompositionLocalProvider(LocalViewModelStoreOwner provides owner, content = content)
    }
}

/** Shown when the APK was built without a Twitch client ID: explains the one-time setup instead of failing. */
@Composable
private fun ConfigMissingScreen() {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 96.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text("S0undPlus needs a Twitch client ID", style = MaterialTheme.typography.headlineMedium, color = Palette.OnBackground)
        Text(
            "This build wasn't given one. Register a free application at dev.twitch.tv/console " +
                "(Client Type: Public), then rebuild with your client ID set as twitchClientId in local.properties " +
                "or as the TWITCH_CLIENT_ID environment variable / GitHub secret. See the README for the full steps.",
            style = MaterialTheme.typography.bodyLarge,
            color = Palette.Muted,
        )
    }
}
