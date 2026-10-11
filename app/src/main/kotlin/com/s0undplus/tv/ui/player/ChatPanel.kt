package com.s0undplus.tv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextOverflow
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
import com.s0undplus.tv.LocalAppContainer
import com.s0undplus.tv.data.AppSettings
import com.s0undplus.tv.ui.theme.Palette
import com.s0undplus.tv.ui.util.Format
import com.s0undplus.twitch.chat.BadgeDirectory
import com.s0undplus.twitch.chat.ChatFormatter
import com.s0undplus.twitch.chat.ChatMentions
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

/** Emote height in ems of the chat font for the Small/Medium/Large emote setting. */
private fun emoteEm(level: Int): Float = when (level) {
    0 -> 1.2f
    2 -> 2.0f
    else -> 1.5f
}

private val TIME_FORMAT = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)

@Composable
fun ChatPanel(controller: ChatController, settings: AppSettings, modifier: Modifier = Modifier) {
    val items by controller.items.collectAsStateWithLifecycle()
    val emotes by controller.emotes.collectAsStateWithLifecycle()
    val badges by controller.badges.collectAsStateWithLifecycle()
    val connected by controller.connected.collectAsStateWithLifecycle()
    val replayError by controller.replayError.collectAsStateWithLifecycle()
    val selfLogin = LocalAppContainer.current.session.tokens.collectAsStateWithLifecycle().value?.login
    val fontSize = chatFontSize(settings.chatTextSize)
    val emoteLevel = settings.emoteSize
    val timestamps = settings.chatTimestamps
    val listState = rememberLazyListState()
    // A keyed lazy list keeps the item it was showing anchored when new ones are added before it, so on its own the
    // newest message (index 0, reversed) would land half below the bottom edge. Chat can't be scrolled by hand on a
    // TV, so always follow the newest one.
    val newestKey = items.lastOrNull()?.key
    LaunchedEffect(newestKey) {
        if (newestKey != null) listState.requestScrollToItem(0)
    }

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
                    ChatLine(item.message, item.deleted, emotes, badges, fontSize, emoteLevel, timestamps, selfLogin)
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
        val status = replayError ?: if (!connected && items.isEmpty() && controller.isLiveChat) "Connecting to chat…" else null
        if (status != null && items.isEmpty()) {
            Text(status, color = Palette.Muted, fontSize = fontSize, modifier = Modifier.align(Alignment.Center))
        }
    }
}

@Composable
private fun ChatLine(
    message: ChatMessage,
    deleted: Boolean,
    emotes: EmoteDirectory,
    badges: BadgeDirectory,
    fontSize: TextUnit,
    emoteLevel: Int,
    timestamps: Boolean,
    selfLogin: String?,
) {
    val built = remember(message, deleted, emotes, badges, emoteLevel, timestamps) {
        buildLine(message, deleted, emotes, badges, emoteLevel, timestamps)
    }
    val mentioned = !deleted && ChatMentions.mentions(message.text, selfLogin)
    val frame = if (mentioned) {
        Modifier.background(Palette.Brand.copy(alpha = 0.28f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp)
    } else {
        Modifier
    }
    Column(Modifier.padding(vertical = 3.dp).then(frame)) {
        val replyTo = message.replyToName
        if (replyTo != null && !deleted) {
            Text(
                "↪ $replyTo: ${message.replyToBody.orEmpty()}",
                fontSize = fontSize * 0.8f,
                color = Palette.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = built.text,
            inlineContent = built.inline,
            fontSize = fontSize,
            lineHeight = fontSize * 1.45f,
            color = Palette.OnBackground,
        )
    }
}

private class BuiltLine(val text: AnnotatedString, val inline: Map<String, InlineTextContent>)

private fun timestampOf(message: ChatMessage): String? = when {
    message.vodOffsetMs != null -> Format.clock(message.vodOffsetMs!! / 1000)
    message.timestampMillis > 0 -> TIME_FORMAT.format(java.util.Date(message.timestampMillis))
    else -> null
}

private fun buildLine(
    message: ChatMessage,
    deleted: Boolean,
    emotes: EmoteDirectory,
    badges: BadgeDirectory,
    emoteLevel: Int,
    timestamps: Boolean,
): BuiltLine {
    val inline = HashMap<String, InlineTextContent>()
    val nameColor = message.colorHex?.let(::parseColor) ?: NAME_COLORS[(message.login.hashCode() and 0x7fffffff) % NAME_COLORS.size]
    val size = emoteEm(emoteLevel).em

    fun image(id: String, url: String) {
        inline[id] = InlineTextContent(
            Placeholder(width = size, height = size, placeholderVerticalAlign = PlaceholderVerticalAlign.Center),
        ) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
    }

    val text = buildAnnotatedString {
        if (timestamps) {
            timestampOf(message)?.let { withStyle(SpanStyle(color = Palette.Muted)) { append("$it ") } }
        }
        message.badges.forEachIndexed { index, badge ->
            val url = badges.url(badge) ?: return@forEachIndexed
            val id = "b$index"
            image(id, url)
            appendInlineContent(id, "[${badge.setId}]")
            append(' ')
        }
        withStyle(SpanStyle(color = if (deleted) Palette.Muted else nameColor, fontWeight = FontWeight.Bold)) {
            append(message.displayName)
        }
        append(if (message.isAction && !deleted) " " else ": ")
        if (deleted) {
            withStyle(SpanStyle(color = Palette.Muted, fontStyle = FontStyle.Italic)) { append("<message deleted>") }
        } else {
            if (message.bits > 0) {
                withStyle(SpanStyle(color = Palette.BrandSoft, fontWeight = FontWeight.Bold)) { append("◆${message.bits} ") }
            }
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
    }
    return BuiltLine(text, inline)
}

private fun parseColor(hex: String): Color? = try {
    Color(android.graphics.Color.parseColor(hex))
} catch (_: IllegalArgumentException) {
    null
}
