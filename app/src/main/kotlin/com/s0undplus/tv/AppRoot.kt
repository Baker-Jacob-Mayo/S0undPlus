package com.s0undplus.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.s0undplus.tv.ui.nav.Route
import com.s0undplus.tv.ui.nav.toPlayTarget
import com.s0undplus.tv.ui.player.PlayerScreen
import com.s0undplus.tv.ui.theme.Palette

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
    // Keyed by account so signing out and in as someone else never shows the previous user's data.
    val nav: NavViewModel = viewModel(key = "nav-$userId")
    val home = viewModel(key = "home-$userId") { HomeViewModel(container) }

    BackHandler(enabled = nav.canPop) { nav.pop() }

    when (val route = nav.current) {
        Route.Home -> HomeScreen(
            vm = home,
            userLogin = login,
            onOpenStream = { nav.push(Route.Play(it.toPlayTarget())) },
            onOpenCategory = { nav.push(Route.Category(it.id, it.name)) },
            onOpenChannel = { nav.push(Route.Channel(it)) },
        )
        is Route.Category -> CategoryScreen(route.id, route.name) { nav.push(Route.Play(it.toPlayTarget())) }
        is Route.Channel -> ChannelScreen(route.login) { nav.push(Route.Play(it)) }
        is Route.Play -> PlayerScreen(route.target, onExit = nav::pop)
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
