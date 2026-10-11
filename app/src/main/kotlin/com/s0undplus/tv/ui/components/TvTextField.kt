package com.s0undplus.tv.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.s0undplus.tv.ui.theme.Palette

/**
 * A single-line text field that behaves on a TV.
 *
 * A text field that is merely *focused* opens the on-screen keyboard by itself, so moving down a list of settings with
 * the D-pad would throw the keyboard over the list every time the cursor passed a field. Here a focused field is an
 * ordinary focusable row (it shows the text, or the [hint]); pressing the center button on it starts editing, which
 * is when the keyboard opens. Done, Back, or moving focus away ends editing and the field is a row again.
 */
@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    imeAction: ImeAction = ImeAction.Done,
    onSubmit: () -> Unit = {},
) {
    var editing by remember { mutableStateOf(false) }
    // Set when editing ends so that focus comes back to the row, instead of being lost with the editor that had it.
    var returnFocusToRow by remember { mutableStateOf(false) }

    // The wrapper lets callers observe focus on the field as a whole, whichever of the two forms is showing.
    Box(modifier) {
        if (editing) {
            Editor(
                value = value,
                onValueChange = onValueChange,
                hint = hint,
                imeAction = imeAction,
                onDone = {
                    editing = false
                    returnFocusToRow = true
                    onSubmit()
                },
                onFocusLost = { editing = false },
            )
        } else {
            FieldRow(value, hint, returnFocus = returnFocusToRow, onFocusReturned = { returnFocusToRow = false }) { editing = true }
        }
    }
}

@Composable
private fun FieldRow(value: String, hint: String, returnFocus: Boolean, onFocusReturned: () -> Unit, onEdit: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(returnFocus) {
        if (returnFocus) {
            try {
                focus.requestFocus()
            } catch (_: IllegalStateException) {
                // not attached yet; the next D-pad press will find it
            }
            onFocusReturned()
        }
    }
    FocusSurface(onClick = onEdit, modifier = Modifier.fillMaxWidth().focusRequester(focus), focusedScale = 1f) {
        Text(
            value.ifEmpty { hint },
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 22.sp),
            color = if (value.isEmpty()) Palette.Muted else Palette.OnBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
        )
    }
}

@Composable
private fun Editor(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    imeAction: ImeAction,
    onDone: () -> Unit,
    onFocusLost: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var hadFocus by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)

    // With the keyboard already hidden by the first Back, a second Back leaves editing instead of leaving the screen.
    BackHandler(onBack = onDone)
    LaunchedEffect(Unit) {
        try {
            focus.requestFocus() // gaining focus is what opens the keyboard
        } catch (_: IllegalStateException) {
            onFocusLost()
        }
    }

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = Palette.OnBackground, fontSize = 22.sp),
        cursorBrush = SolidColor(Palette.Brand),
        keyboardOptions = KeyboardOptions(imeAction = imeAction),
        keyboardActions = KeyboardActions(
            onDone = {
                keyboard?.hide()
                onDone()
            },
            onSearch = {
                keyboard?.hide()
                onDone()
            },
        ),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focus)
            .onFocusChanged { state ->
                if (state.isFocused) hadFocus = true else if (hadFocus) onFocusLost()
            },
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Palette.SurfaceFocused, shape)
                    .border(3.dp, Palette.Brand, shape)
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            ) {
                if (value.isEmpty()) {
                    Text(hint, style = MaterialTheme.typography.titleMedium, color = Palette.Muted)
                }
                inner()
            }
        },
    )
}
