package com.s0undplus.tv.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.s0undplus.tv.ui.theme.Palette

/** Renders [content] as a crisp black-on-white QR code bitmap. */
fun qrBitmap(content: String, size: Int = 512): ImageBitmap {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 0))
    val pixels = IntArray(size * size) { i ->
        if (matrix[i % size, i / size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}

@Composable
fun QrImage(content: String, modifier: Modifier = Modifier.size(260.dp)) {
    val qr = remember(content) { qrBitmap(content) }
    Image(
        bitmap = qr,
        contentDescription = "QR code",
        filterQuality = FilterQuality.None,
        modifier = modifier.clip(RoundedCornerShape(12.dp)).background(Color.White).padding(10.dp),
    )
}

/** A full-screen dialog showing a QR code to scan with a phone. Back or OK closes it. */
@Composable
fun QrDialog(title: String, subtitle: String, url: String, onDismiss: () -> Unit) {
    val focus = remember { FocusRequester() }
    BackHandler(onBack = onDismiss)
    LaunchedEffect(Unit) {
        try {
            focus.requestFocus()
        } catch (_: IllegalStateException) {
            // not attached yet
        }
    }
    Box(Modifier.fillMaxSize().background(Palette.Scrim).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(Palette.Surface)
                .padding(40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = Palette.OnBackground)
            QrImage(url, Modifier.size(300.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Palette.Muted, textAlign = TextAlign.Center)
            Text(url, style = MaterialTheme.typography.bodySmall, color = Palette.BrandSoft)
            PillButton("Close", onDismiss, Modifier.focusRequester(focus))
        }
    }
}
