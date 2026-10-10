package com.s0undplus.tv.ui.components

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.s0undplus.tv.ui.theme.Palette

/**
 * A single-line text field that behaves on a TV: it takes D-pad focus like everything else and opens the on-screen
 * keyboard when you press the center button on it.
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
    var focused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val shape = RoundedCornerShape(12.dp)

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
                onSubmit()
            },
            onSearch = {
                keyboard?.hide()
                onSubmit()
            },
        ),
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                val confirm = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
                if (confirm && event.type == KeyEventType.KeyUp) {
                    keyboard?.show()
                    true
                } else {
                    false
                }
            },
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(if (focused) Palette.SurfaceFocused else Palette.Surface, shape)
                    .border(3.dp, if (focused) Palette.Brand else Color.Transparent, shape)
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
