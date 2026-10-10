package com.s0undplus.tv.ui.login

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
            } catch (_: IOException) {
                state = LoginState.Failed("Couldn't reach Twitch. Check your connection and try again.")
                return@LaunchedEffect
            }
        }
    }

    Row(
        Modifier.fillMaxSize().padding(horizontal = 96.dp, vertical = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(72.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("S0und+", style = MaterialTheme.typography.displayMedium, color = Palette.Brand, fontWeight = FontWeight.Bold)
            Text("Sign in to Twitch", style = MaterialTheme.typography.headlineMedium, color = Palette.OnBackground)
            Text(
                "Scan the code with your phone, or go to twitch.tv/activate on any device and enter the code shown. " +
                    "S0undPlus only asks to read which channels you follow.",
                style = MaterialTheme.typography.bodyLarge,
                color = Palette.Muted,
            )
            when (val s = state) {
                is LoginState.Failed -> {
                    Text(s.message, color = Palette.Live, style = MaterialTheme.typography.bodyLarge)
                    PillButton("Try again", { attempt++ })
                }
                else -> Unit
            }
        }

        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            when (val s = state) {
                is LoginState.WaitingForViewer -> CodePanel(s.code)
                LoginState.Requesting -> Spinner()
                is LoginState.Failed -> Unit
            }
        }
    }
}

@Composable
private fun CodePanel(code: DeviceCode) {
    QrImage(code.verificationUri)
    Text(
        code.userCode,
        fontSize = 56.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 8.sp,
        color = Palette.OnBackground,
        textAlign = TextAlign.Center,
    )
    Text("twitch.tv/activate", style = MaterialTheme.typography.titleMedium, color = Palette.BrandSoft)
    Text("Waiting for you to approve…", style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
}
