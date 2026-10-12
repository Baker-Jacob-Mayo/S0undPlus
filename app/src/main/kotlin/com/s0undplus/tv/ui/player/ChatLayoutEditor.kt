package com.s0undplus.tv.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.s0undplus.tv.data.ChatLayout
import com.s0undplus.tv.data.ChatPlacement
import com.s0undplus.tv.data.ChatPosition
import com.s0undplus.tv.ui.components.PillButton
import com.s0undplus.tv.ui.theme.AppType
import com.s0undplus.tv.ui.theme.Palette

/** What the arrows do in the chat editor: move between its buttons, or move or resize chat. */
private enum class EditMode { BUTTONS, MOVE, RESIZE }

/**
 * Places chat over the playing stream: a row of buttons (side, beside or over the video, Move, Resize, Done). Move and
 * Resize hand the arrows to chat itself until OK or Back. Every change is shown at once through [onChange]; Done or
 * Back on the buttons calls [onDone], which keeps it.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ChatLayoutEditor(
    placement: ChatPlacement,
    onChange: (ChatPlacement) -> Unit,
    onDone: () -> Unit,
    firstButton: FocusRequester,
    modifier: Modifier = Modifier,
) {
    var mode by remember { mutableStateOf(EditMode.BUTTONS) }
    val moveButton = remember { FocusRequester() }
    val resizeButton = remember { FocusRequester() }
    // Back from moving or resizing returns to the buttons; Back on the buttons is Done.
    BackHandler { if (mode == EditMode.BUTTONS) onDone() else mode = EditMode.BUTTONS }
    LaunchedEffect(mode) {
        // While chat takes the arrows, focus waits on the button that started it.
        try {
            when (mode) {
                EditMode.MOVE -> moveButton.requestFocus()
                EditMode.RESIZE -> resizeButton.requestFocus()
                EditMode.BUTTONS -> Unit
            }
        } catch (_: IllegalStateException) {
            // not attached yet
        }
    }
    Box(
        modifier
            .fillMaxSize()
            .onPreviewKeyEvent { e ->
                if (mode == EditMode.BUTTONS || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val step = when (e.key) {
                    Key.DirectionLeft -> -1 to 0
                    Key.DirectionRight -> 1 to 0
                    Key.DirectionUp -> 0 to -1
                    Key.DirectionDown -> 0 to 1
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        mode = EditMode.BUTTONS
                        return@onPreviewKeyEvent true
                    }
                    else -> return@onPreviewKeyEvent false
                }
                onChange(if (mode == EditMode.MOVE) placement.moved(step.first, step.second) else placement.resized(step.first, step.second))
                true
            },
    ) {
        Text(
            hint(mode, placement),
            style = AppType.BodyL,
            color = Palette.OnBackground,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 28.dp)
                .background(Palette.Scrim, RoundedCornerShape(50))
                .padding(horizontal = 20.dp, vertical = 10.dp),
        )
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 40.dp)
                .focusProperties { exit = { FocusRequester.Cancel } }
                .focusGroup()
                .background(Palette.SurfaceRaised, RoundedCornerShape(50))
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PillButton(
                "Position: ${placement.position.label}",
                { onChange(placement.copy(position = ChatPosition.entries[(placement.position.ordinal + 1) % ChatPosition.entries.size])) },
                Modifier.focusRequester(firstButton),
            )
            if (!placement.floating) {
                PillButton(placement.style.label, {
                    onChange(placement.copy(style = if (placement.style == ChatLayout.SIDE) ChatLayout.OVERLAY else ChatLayout.SIDE))
                })
            }
            PillButton("Move", { mode = EditMode.MOVE }, Modifier.focusRequester(moveButton), selected = mode == EditMode.MOVE)
            PillButton("Resize", { mode = EditMode.RESIZE }, Modifier.focusRequester(resizeButton), selected = mode == EditMode.RESIZE)
            PillButton("Done", onDone)
        }
    }
}

private fun hint(mode: EditMode, p: ChatPlacement): String = when (mode) {
    EditMode.BUTTONS -> "Adjust chat: choose Move or Resize, then use the arrows. Back when done."
    EditMode.MOVE -> if (p.floating) "Arrows move chat  ·  OK when done" else "↑ ↓ along the side  ·  ← → the other side  ·  OK when done"
    EditMode.RESIZE -> if (p.beside) "← → narrower, wider  ·  OK when done" else "← → narrower, wider  ·  ↑ ↓ taller, shorter  ·  OK when done"
}
