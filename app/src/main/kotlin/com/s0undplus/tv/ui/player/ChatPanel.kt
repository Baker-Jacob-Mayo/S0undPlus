package com.s0undplus.tv.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.s0undplus.tv.ui.theme.Palette
import com.s0undplus.twitch.chat.BadgeDirectory
import com.s0undplus.twitch.chat.ChatFormatter
import com.s0undplus.twitch.chat.ChatMessage
import com.s0undplus.twitch.chat.ChatSegment
import com.s0undplus.twitch.chat.EmoteDirectory

private val NAME_COLORS = listOf(
    0xFFFF4F4F, 0xFF4F8CFF, 0xFF2ECC71, 0xFFE67E22, 0xFFE056FD, 0xFF1ABC9C, 0xFFFFC312, 0xFFFF7979,
).map { Color(it) }

/** Chat text sizes for the Small/Medium/Large setting, tuned for reading across a living room. */
fun chatFontSize(level: Int): TextUnit = when (level) {
    0 -> 15.sp
    2 -> 22.sp
    else -> 18.sp
}

@Composable
fun ChatPanel(controller: ChatController, textSizeLevel: Int, modifier: Modifier = Modifier) {
    val items by controller.items.collectAsStateWithLifecycle()
    val emotes by controller.emotes.collectAsStateWithLifecycle()
    val badges by controller.badges.collectAsStateWithLifecycle()
    val connected by controller.connected.collectAsStateWithLifecycle()
    val fontSize = chatFontSize(textSizeLevel)
    val listState = rememberLazyListState()

    Box(modifier) {
        // reverseLayout pins the newest message to the bottom without any manual scrolling logic.
        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            items(items.asReversed(), key = { it.key }) { item ->
                if (item.message != null) {
                    ChatLine(item.message, emotes, badges, fontSize)
                } else {
                    Text(
                        item.notice.orEmpty(),
                        fontSize = fontSize,
                        fontStyle = FontStyle.Italic,
                        color = Palette.BrandSoft,
                        modifier = Modifier.padding(vertical = 3.dp),
                    )
                }
            }
        }
        if (!connected && items.isEmpty()) {
            Text("Connecting to chat…", color = Palette.Muted, fontSize = fontSize, modifier = Modifier.align(Alignment.Center))
        }
    }
}

@Composable
private fun ChatLine(message: ChatMessage, emotes: EmoteDirectory, badges: BadgeDirectory, fontSize: TextUnit) {
    val built = remember(message, emotes, badges) { buildLine(message, emotes, badges) }
    Text(
        text = built.text,
        inlineContent = built.inline,
        fontSize = fontSize,
        lineHeight = fontSize * 1.45f,
        color = Palette.OnBackground,
        modifier = Modifier.padding(vertical = 3.dp),
    )
}

private class BuiltLine(val text: AnnotatedString, val inline: Map<String, InlineTextContent>)

private fun buildLine(message: ChatMessage, emotes: EmoteDirectory, badges: BadgeDirectory): BuiltLine {
    val inline = HashMap<String, InlineTextContent>()
    val nameColor = message.colorHex?.let(::parseColor) ?: NAME_COLORS[(message.login.hashCode() and 0x7fffffff) % NAME_COLORS.size]

    fun image(id: String, url: String) {
        inline[id] = InlineTextContent(
            Placeholder(width = 1.4.em, height = 1.4.em, placeholderVerticalAlign = PlaceholderVerticalAlign.Center),
        ) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
    }

    val text = buildAnnotatedString {
        message.badges.forEachIndexed { index, badge ->
            val url = badges.url(badge) ?: return@forEachIndexed
            val id = "b$index"
            image(id, url)
            appendInlineContent(id, "[${badge.setId}]")
            append(' ')
        }
        withStyle(SpanStyle(color = nameColor, fontWeight = FontWeight.Bold)) { append(message.displayName) }
        append(if (message.isAction) " " else ": ")
        val body = ChatFormatter.format(message, emotes)
        val textStyle = if (message.isAction) SpanStyle(color = nameColor, fontStyle = FontStyle.Italic) else SpanStyle()
        body.forEachIndexed { index, segment ->
            when (segment) {
                is ChatSegment.Text -> withStyle(textStyle) { append(segment.text) }
                is ChatSegment.EmoteImage -> {
                    val id = "e$index"
                    image(id, segment.url)
                    appendInlineContent(id, segment.name)
                }
            }
        }
    }
    return BuiltLine(text, inline)
}

private fun parseColor(hex: String): Color? = try {
    Color(android.graphics.Color.parseColor(hex))
} catch (_: IllegalArgumentException) {
    null
}
