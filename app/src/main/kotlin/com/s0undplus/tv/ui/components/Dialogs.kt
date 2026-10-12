package com.s0undplus.tv.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.s0undplus.tv.ui.theme.AppType
import com.s0undplus.tv.ui.theme.Palette
import com.s0undplus.tv.ui.theme.Radius
import com.s0undplus.twitch.TwitchLanguages

/**
 * A dialog panel over a dimmed page. The D-pad cannot wander out of it to the page behind, and Back closes it.
 * [initialFocus] is focused when it opens. The dialog belongs to the screen beside it: when it closes, focus would be
 * left with nothing, so it is handed back to [returnFocus] (the control that opened it) if given.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DialogFrame(
    onDismiss: () -> Unit,
    initialFocus: FocusRequester,
    modifier: Modifier = Modifier,
    returnFocus: FocusRequester? = null,
    content: @Composable () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    if (returnFocus != null) {
        DisposableEffect(Unit) {
            onDispose {
                try {
                    returnFocus.requestFocus()
                } catch (_: IllegalStateException) {
                    // the opener is gone too; nothing to return to
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        try {
            initialFocus.requestFocus()
        } catch (_: IllegalStateException) {
            // not attached yet; the D-pad still reaches the dialog
        }
    }
    Box(Modifier.fillMaxSize().background(Palette.Scrim), contentAlignment = Alignment.Center) {
        Box(
            modifier
                .focusProperties { exit = { FocusRequester.Cancel } }
                .focusGroup()
                .clip(RoundedCornerShape(Radius.dialog))
                .background(Palette.SurfaceRaised)
                .padding(28.dp),
        ) { content() }
    }
}

/** A QR code to scan with a phone, with what it is for. Back or Close dismisses it. */
@Composable
fun QrDialog(title: String, subtitle: String, url: String, onDismiss: () -> Unit, returnFocus: FocusRequester? = null) {
    val focus = remember { FocusRequester() }
    DialogFrame(onDismiss, focus, Modifier.width(640.dp), returnFocus) {
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
            QrImage(url, Modifier.size(232.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = AppType.TitleL, color = Palette.OnBackground)
                Text(subtitle, style = AppType.BodyM, color = Palette.Muted)
                Text(url, style = AppType.BodyM, color = Palette.BrandSoft)
                Box(Modifier.padding(top = 8.dp)) { PillButton("Close", onDismiss, Modifier.focusRequester(focus)) }
            }
        }
    }
}
