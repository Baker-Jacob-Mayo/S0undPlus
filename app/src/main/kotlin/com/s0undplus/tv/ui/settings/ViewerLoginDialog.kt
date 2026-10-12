package com.s0undplus.tv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.s0undplus.tv.data.VideoDecoders
import com.s0undplus.tv.data.ViewerLogin
import com.s0undplus.tv.ui.components.DialogFrame
import com.s0undplus.tv.ui.components.PillButton
import com.s0undplus.tv.ui.components.QrImage
import com.s0undplus.tv.ui.components.Spinner
import com.s0undplus.tv.ui.components.describe
import com.s0undplus.tv.ui.theme.AppType
import com.s0undplus.tv.ui.theme.Palette
import com.s0undplus.twitch.auth.DeviceCode
import com.s0undplus.twitch.auth.DeviceLoginException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch

/** What the 1440p row says: on (and as whom), off, or not possible on this TV. */
fun viewerLoginLabel(login: String?): String = when {
    !VideoDecoders.hevc1440 -> "This TV can't play it"
    login != null -> "On · $login"
    else -> "Off"
}

/**
 * Turns 1440p on or off. On: a second sign-in with a code at twitch.tv/activate, this time to Twitch's own TV app,
 * shown as a QR code like the first one. Signed in: who as, and Sign out.
 */
@Composable
fun ViewerLoginDialog(viewer: ViewerLogin, onDismiss: () -> Unit, returnFocus: FocusRequester?) {
    val signedIn by viewer.tokens.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    var code by remember { mutableStateOf<DeviceCode?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }

    // Signed out: ask Twitch for a code and wait for it to be entered, for as long as the dialog is open.
    LaunchedEffect(signedIn == null, attempt) {
        if (signedIn != null) return@LaunchedEffect
        code = null
        problem = null
        try {
            val c = viewer.requestCode()
            code = c
            viewer.awaitSignIn(c)
        } catch (e: CancellationException) {
            throw e
        } catch (e: DeviceLoginException) {
            problem = e.message
        } catch (e: Exception) {
            problem = describe(e)
        }
    }

    DialogFrame(onDismiss, first, Modifier.width(720.dp), returnFocus) {
        val who = signedIn
        if (who != null) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("1440p is on", style = AppType.TitleL, color = Palette.OnBackground)
                Text(
                    "Signed in to Twitch's TV app as ${who.login}, for playback only. Streams sent in 2K now offer 1440p " +
                        "in Quality. If you have Turbo or subscribe to a channel, its ads go too.",
                    style = AppType.BodyM,
                    color = Palette.Muted,
                )
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PillButton("Close", onDismiss, Modifier.focusRequester(first))
                    PillButton("Sign out", { scope.launch { viewer.signOut() } })
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(216.dp), contentAlignment = Alignment.Center) {
                    val c = code
                    if (c != null) QrImage(c.verificationUri, Modifier.size(216.dp)) else if (problem == null) Spinner()
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Sign in for 1440p", style = AppType.TitleL, color = Palette.OnBackground)
                    Text(
                        "Twitch sends 1440p only to signed-in viewers of its own apps. This signs in once more, to " +
                            "Twitch's TV app, and uses it only to ask for streams. It's unofficial, so Twitch could stop it.",
                        style = AppType.BodyM,
                        color = Palette.Muted,
                    )
                    val c = code
                    when {
                        problem != null -> Text(problem.orEmpty(), style = AppType.BodyM, color = Palette.Live)
                        c != null -> {
                            Text("Scan the code, or go to twitch.tv/activate and enter", style = AppType.BodyM, color = Palette.Muted)
                            Text(c.userCode, style = AppType.Headline, color = Palette.BrandSoft)
                        }
                    }
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PillButton("Cancel", onDismiss, Modifier.focusRequester(first))
                        if (problem != null) PillButton("Try again", { attempt++ })
                    }
                }
            }
        }
    }
}
