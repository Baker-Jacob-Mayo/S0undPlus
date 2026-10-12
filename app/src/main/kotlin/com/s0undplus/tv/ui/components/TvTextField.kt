package com.s0undplus.tv.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.tv.material3.Icon
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
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
    leadingIcon: ImageVector? = null,
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
                // Backing out is not submitting: it leaves whatever was typed in place and does nothing else.
                onBack = {
                    editing = false
                    returnFocusToRow = true
                },
                onFocusLost = { editing = false },
            )
        } else {
            FieldRow(value, hint, leadingIcon, returnFocus = returnFocusToRow, onFocusReturned = { returnFocusToRow = false }) { editing = true }
        }
    }
}

@Composable
private fun FieldRow(value: String, hint: String, icon: ImageVector?, returnFocus: Boolean, onFocusReturned: () -> Unit, onEdit: () -> Unit) {
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
    // Like every list row: grey until focused, then white with dark text. OK starts typing.
    ListRowSurface(onClick = onEdit, modifier = Modifier.fillMaxWidth().focusRequester(focus), container = Palette.Surface) { focused ->
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (focused) Palette.OnFocusFill else Palette.Muted,
                modifier = Modifier.padding(start = 18.dp).size(24.dp),
            )
        }
        Text(
            value.ifEmpty { hint },
            style = FIELD_TEXT,
            color = when {
                focused && value.isEmpty() -> Palette.OnFocusFillSecondary
                focused -> Palette.OnFocusFill
                value.isEmpty() -> Palette.Muted
                else -> Palette.OnBackground
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = if (icon != null) 14.dp else 20.dp, vertical = 14.dp),
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
    onBack: () -> Unit,
    onFocusLost: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var hadFocus by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)

    // With the keyboard already hidden by the first Back, a second Back leaves editing instead of leaving the screen.
    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) {
        try {
            focus.requestFocus() // gaining focus is what opens the keyboard
        } catch (_: IllegalStateException) {
            onFocusLost()
        }
    }

    // Editing starts with the cursor after the existing text (the plain-String overload would put it at the start, so
    // anything typed would land in front of what was already there).
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    val shown = if (field.text == value) field else TextFieldValue(value, TextRange(value.length))

    BasicTextField(
        value = shown,
        onValueChange = {
            field = it
            if (it.text != value) onValueChange(it.text)
        },
        singleLine = true,
        textStyle = FIELD_TEXT.copy(color = Palette.OnBackground),
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
                    .border(2.dp, Palette.Brand, shape)
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            ) {
                if (value.isEmpty()) {
                    Text(hint, style = FIELD_TEXT, color = Palette.Muted)
                }
                inner()
            }
        },
    )
}

private val FIELD_TEXT = TextStyle(fontSize = 18.sp, lineHeight = 24.sp)
