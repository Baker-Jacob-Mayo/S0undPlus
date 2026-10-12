package com.s0undplus.tv.ui.login

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.TextStyle
import androidx.tv.material3.Icon
import com.s0undplus.tv.ui.theme.AppType
import com.s0undplus.tv.ui.theme.Layout
import com.s0undplus.tv.ui.theme.Radius
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.LaunchedEffect
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.s0undplus.tv.LocalAppContainer
import com.s0undplus.tv.ui.components.PillButton
import com.s0undplus.tv.ui.components.QrImage
import com.s0undplus.tv.ui.components.Spinner
import com.s0undplus.tv.ui.theme.Palette
import com.s0undplus.twitch.TwitchApiException
import com.s0undplus.twitch.TwitchScopes
import com.s0undplus.twitch.auth.DeviceCode
import com.s0undplus.twitch.auth.DeviceLoginException
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

private sealed interface LoginState {
    data object Requesting : LoginState
    data class WaitingForViewer(val code: DeviceCode) : LoginState
    data class Failed(val message: String) : LoginState
}

/** Device-code login: scan the QR code (or type the short code at twitch.tv/activate) on your phone. */
@Composable
fun LoginScreen() {
    val container = LocalAppContainer.current
    var attempt by remember { mutableStateOf(0) }
    var state by remember { mutableStateOf<LoginState>(LoginState.Requesting) }

    LaunchedEffect(attempt) {
        state = LoginState.Requesting
        while (true) {
            try {
                val code = container.auth.requestDeviceCode(TwitchScopes.DEFAULT)
                state = LoginState.WaitingForViewer(code)
                val tokens = container.auth.awaitDeviceLogin(code, TwitchScopes.DEFAULT)
                container.session.signIn(tokens)
                return@LaunchedEffect
            } catch (e: CancellationException) {
                throw e
            } catch (_: DeviceLoginException.Expired) {
                continue // the code timed out: quietly fetch a fresh one
            } catch (_: DeviceLoginException.Denied) {
                state = LoginState.Failed("Sign-in was cancelled on Twitch.")
                return@LaunchedEffect
            } catch (e: TwitchApiException) {
                // Twitch answered, so the network is fine: a 400 here usually means a wrong or disabled client ID.
                state = LoginState.Failed("Twitch rejected the request (HTTP ${e.status}). Check the client ID this build was made with.")
                return@LaunchedEffect
            } catch (_: IOException) {
                state = LoginState.Failed("Couldn't reach Twitch. Check your connection and try again.")
                return@LaunchedEffect
            } catch (_: Exception) {
                // e.g. a reply that isn't JSON: show it instead of letting it escape the effect and crash the app
                state = LoginState.Failed("Twitch sent an unexpected reply. Try again.")
                return@LaunchedEffect
            }
        }
    }

    val retry = remember { FocusRequester() }
    val failed = state is LoginState.Failed
    LaunchedEffect(failed) {
        if (failed) {
            withFrameNanos { }
            try {
                retry.requestFocus()
            } catch (_: IllegalStateException) {
                // not attached yet; the D-pad still reaches the button
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(Palette.Brand.copy(alpha = 0.22f), Color.Transparent), end = Offset(1400f, 1000f))),
    ) {
        Row(
            Modifier.fillMaxSize().padding(start = Layout.keyline, end = Layout.endMargin, top = 48.dp, bottom = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("S0und+", style = AppType.Wordmark.copy(fontSize = 44.sp, lineHeight = 48.sp), color = Palette.Brand)
                Text("Sign in to Twitch", style = AppType.Headline, color = Palette.OnBackground, modifier = Modifier.padding(bottom = 8.dp))
                Step(1, "Scan the code with your phone")
                Step(2, "Or open twitch.tv/activate on any device and enter the code")
                Step(3, "Approve, and you're in")
                Text(
                    "S0undPlus only asks to read which channels you follow.",
                    style = AppType.BodyM,
                    color = Palette.TextTertiary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Column(
                Modifier
                    .width(380.dp)
                    .clip(RoundedCornerShape(Radius.dialog))
                    .background(Palette.SurfaceRaised)
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when (val s = state) {
                    is LoginState.WaitingForViewer -> CodePanel(s.code)
                    LoginState.Requesting -> Box(Modifier.size(320.dp), contentAlignment = Alignment.Center) { Spinner() }
                    is LoginState.Failed -> {
                        Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = Palette.LiveText, modifier = Modifier.size(40.dp))
                        Text(s.message, color = Palette.OnBackground, style = AppType.BodyL, textAlign = TextAlign.Center)
                        PillButton("Try again", { attempt++ }, Modifier.focusRequester(retry))
                    }
                }
            }
        }
    }
}

@Composable
private fun Step(number: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(Palette.SurfaceFocused), contentAlignment = Alignment.Center) {
            Text("$number", style = AppType.Button, color = Palette.BrandSoft)
        }
        Text(text, style = AppType.BodyL, color = Palette.OnBackground)
    }
}

@Composable
private fun CodePanel(code: DeviceCode) {
    QrImage(code.verificationUri, Modifier.size(216.dp))
    BasicText(
        code.userCode,
        // Shrinks to fit, so the widest 8-letter code (WMWMWMWM) never breaks onto a second line.
        style = TextStyle(color = Palette.OnBackground, fontWeight = FontWeight.ExtraBold, letterSpacing = 3.sp, textAlign = TextAlign.Center),
        maxLines = 1,
        softWrap = false,
        autoSize = TextAutoSize.StepBased(minFontSize = 24.sp, maxFontSize = 40.sp),
        modifier = Modifier.fillMaxWidth(),
    )
    Text("twitch.tv/activate", style = AppType.RowTitle, color = Palette.BrandSoft)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Spinner(size = 16.dp)
        Text("Waiting for you to approve…", style = AppType.BodyM, color = Palette.Muted)
    }
}
