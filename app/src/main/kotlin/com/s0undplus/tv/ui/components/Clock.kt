package com.s0undplus.tv.ui.components

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.s0undplus.tv.ui.theme.Palette
import java.util.Date
import kotlinx.coroutines.delay

/** The time of day in the TV's own 12/24-hour format, refreshed just after each minute boundary. */
@Composable
fun ClockText(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val formatter = DateFormat.getTimeFormat(context)
    val now by produceState(Date()) {
        while (true) {
            value = Date()
            delay(60_000 - (System.currentTimeMillis() % 60_000) + 50)
        }
    }
    Text(formatter.format(now), style = MaterialTheme.typography.titleMedium, color = Palette.OnBackground, modifier = modifier)
}
