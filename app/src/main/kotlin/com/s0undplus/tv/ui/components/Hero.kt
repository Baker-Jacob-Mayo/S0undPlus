package com.s0undplus.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.s0undplus.tv.LocalAppContainer
import com.s0undplus.tv.ui.theme.AppType
import com.s0undplus.tv.ui.theme.Layout
import com.s0undplus.tv.ui.theme.Motion
import com.s0undplus.tv.ui.theme.Palette
import com.s0undplus.tv.ui.theme.Radius
import com.s0undplus.tv.ui.util.Format
import com.s0undplus.twitch.Category
import com.s0undplus.twitch.Stream
import com.s0undplus.twitch.TwitchLanguages
import com.s0undplus.twitch.library.HistoryEntry
import androidx.compose.runtime.withFrameNanos
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/** What the hero describes: the card that has focus. [section] is the row it came from, shown above the name. */
sealed interface HeroModel {
    val section: String

    data class Live(val stream: Stream, val avatarUrl: String, val favorite: Boolean, override val section: String) : HeroModel

    data class Channel(
        val displayName: String,
        val avatarUrl: String,
        val description: String,
        val favorite: Boolean,
        override val section: String,
    ) : HeroModel

    data class Vod(val entry: HistoryEntry, override val section: String) : HeroModel

    /** A category followed in the app, shown among the followed channels. */
    data class CategoryItem(val category: Category, override val section: String) : HeroModel
}

/**
 * The hero's subject. Cards write [target] when they gain focus; the hero only follows once focus has rested for
 * [Motion.HERO_SETTLE_MS], so holding a direction key never makes it reload pictures and text on every step.
 * Nothing but the hero reads it, so writing it never recomposes the cards.
 */
@Stable
class HeroState {
    var target by mutableStateOf<HeroModel?>(null)
    var shown by mutableStateOf<HeroModel?>(null)
        private set

    /** What the backdrop shows: follows [shown] one frame later, so the text and the picture don't change in the same frame. */
    var backdrop by mutableStateOf<HeroModel?>(null)
        private set

    /** Each category's top stream when it was last looked up, so moving back and forth doesn't ask Twitch again. */
    internal val topStreams = HashMap<String, Pair<Long, Stream?>>()

    /** Forgets what was shown, so the page shows its first card again (Back to the navigation). */
    fun reset() {
        target = null
        shown = null
        backdrop = null
    }

    /** Shows [model] straight away (the first item, or the card focus was restored to). */
    fun showNow(model: HeroModel) {
        target = model
        shown = model
        backdrop = model
    }

    internal suspend fun follow() {
        snapshotFlow { target }.collectLatest { model ->
            if (model == null || model == shown) return@collectLatest
            if (shown != null) delay(Motion.HERO_SETTLE_MS)
            shown = model
            // The text this frame, the picture the next: each frame does half the work.
            withFrameNanos { }
            backdrop = model
        }
    }
}

/** Fades for the backdrop, built from the page colour so every theme gets matching ones. */
private fun Modifier.backdropFades(background: Color): Modifier = drawWithCache {
    // Stream pictures are busy (overlays, chat, webcams): dim them so the hero's text and the rows stay in front.
    val dim = background.copy(alpha = 0.35f)
    val left = Brush.horizontalGradient(
        0f to background,
        0.22f to background.copy(alpha = 0.9f),
        0.5f to background.copy(alpha = 0.45f),
        0.8f to Color.Transparent,
    )
    val bottom = Brush.verticalGradient(0.45f to Color.Transparent, 0.78f to background.copy(alpha = 0.8f), 1f to background)
    val top = Brush.verticalGradient(0f to background.copy(alpha = 0.5f), 0.22f to Color.Transparent)
    onDrawWithContent {
        drawContent()
        drawRect(dim)
        drawRect(left)
        drawRect(bottom)
        drawRect(top)
    }
}

/**
 * The backdrop behind the hero: the focused stream's picture (the card's own smaller copy shows at once, then the
 * sharp one fades in), or for channels and videos a soft brand-coloured wash with the avatar or thumbnail inset.
 * Place it at the top-right of the page, behind everything else.
 */
@Composable
fun HeroBackdrop(state: HeroState, modifier: Modifier = Modifier) {
    LaunchedEffect(state) { state.follow() }
    val model = state.backdrop
    // Drawn once into its own cached layer: the picture and its four fades are only redrawn when the picture changes,
    // not on every frame of the rows scrolling in front of it (that was most of the GPU's time on a TV box).
    Box(modifier.width(640.dp).height(360.dp).graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        when (model) {
            is HeroModel.Live -> {
                val context = LocalPlatformContext.current
                val small = model.stream.thumbnail()
                val request = remember(small) {
                    ImageRequest.Builder(context)
                        .data(model.stream.thumbnail(1280, 720))
                        .placeholderMemoryCacheKey(small)
                        .build()
                }
                AsyncImage(request, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            is HeroModel.Channel -> {
                Box(Modifier.fillMaxSize().background(wash()))
                Avatar(model.avatarUrl, model.displayName, Modifier.align(Alignment.Center).offset(x = 60.dp, y = (-20).dp).size(168.dp))
            }
            is HeroModel.Vod -> {
                Box(Modifier.fillMaxSize().background(wash()))
                CardImage(
                    model.entry.thumbnailUrl,
                    Modifier
                        .align(Alignment.Center)
                        .offset(x = 60.dp, y = (-24).dp)
                        .size(288.dp, 162.dp)
                        .clip(RoundedCornerShape(Radius.row)),
                )
            }
            is HeroModel.CategoryItem -> {
                Box(Modifier.fillMaxSize().background(wash()))
                CardImage(
                    model.category.boxArt(270, 360),
                    Modifier
                        .align(Alignment.Center)
                        .offset(x = 60.dp, y = (-20).dp)
                        .size(132.dp, 176.dp)
                        .clip(RoundedCornerShape(Radius.card)),
                )
            }
            null -> Unit
        }
        Box(Modifier.fillMaxSize().backdropFades(Palette.Background))
    }
}

@Composable
private fun wash(): Brush = Brush.linearGradient(listOf(Palette.Brand.copy(alpha = 0.28f), Color.Transparent))

/** The hero's text: what the focused card is, in full. Fixed height; not focusable. */
@Composable
fun HeroInfo(state: HeroState, modifier: Modifier = Modifier) {
    val model = state.shown
    Column(
        modifier
            .fillMaxWidth()
            .height(Layout.heroHeight)
            .padding(start = Layout.keyline, top = 40.dp, end = 400.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (model == null) return@Column
        Text(model.section.uppercase(), style = AppType.Overline, color = Palette.BrandSoft)
        when (model) {
            is HeroModel.Live -> LiveInfo(model)
            is HeroModel.Channel -> ChannelInfo(model)
            is HeroModel.Vod -> VodInfo(model)
            is HeroModel.CategoryItem -> CategoryInfo(model, state)
        }
    }
}

@Composable
private fun CategoryInfo(model: HeroModel.CategoryItem, state: HeroState) {
    val helix = LocalAppContainer.current.helix
    val id = model.category.id
    // What's on in the category right now: its most-watched stream, looked up once every few minutes at most.
    val top by produceState(state.topStreams[id]?.second, id) {
        // The state outlives a change of category: start from this one's answer, never the last one's.
        val cached = state.topStreams[id]
        value = cached?.second
        if (cached != null && System.currentTimeMillis() - cached.first < TOP_STREAM_FRESH_MS) return@produceState
        value = try {
            helix.streams(gameId = id, first = 1).items.firstOrNull().also {
                state.topStreams[id] = System.currentTimeMillis() to it
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
    NameLine(model.category.name, "")
    Text("A category you follow in S0undPlus", style = AppType.BodyM, color = Palette.TextTertiary)
    top?.let { s ->
        Text(
            "Top stream now: ${s.userName} · ${Format.count(s.viewerCount)} viewers",
            style = AppType.BodyL,
            color = Palette.Muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    Text("OK opens ${model.category.name}", style = AppType.BodyM, color = Palette.TextTertiary)
}

private const val TOP_STREAM_FRESH_MS = 5 * 60_000L

@Composable
private fun NameLine(name: String, avatarUrl: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (avatarUrl.isNotBlank()) Avatar(avatarUrl, name, Modifier.size(40.dp))
        Text(name, style = AppType.Display, color = Palette.OnBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun LiveInfo(model: HeroModel.Live) {
    val s = model.stream
    NameLine(s.userName, model.avatarUrl)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LiveBadge()
        val parts = listOfNotNull(
            "${Format.count(s.viewerCount)} viewers",
            Format.uptime(s.startedAt),
            s.language.takeIf { it.isNotBlank() }?.let { TwitchLanguages.name(it) },
        )
        Text(parts.joinToString(" · "), style = AppType.BodyM, color = Palette.OnBackground, maxLines = 1)
        if (s.isMature) ImageBadge("18+", background = Palette.SurfaceFocused)
    }
    if (s.title.isNotBlank()) {
        Text(s.title, style = AppType.BodyL, color = Palette.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 460.dp))
    }
    SubLine(s.gameName, model.favorite)
}

@Composable
private fun ChannelInfo(model: HeroModel.Channel) {
    NameLine(model.displayName, "")
    // The Offline row already says so above the name; favourites (and others) say it here.
    if (!model.section.equals("Offline", ignoreCase = true)) Text("Offline", style = AppType.BodyM, color = Palette.TextTertiary)
    if (model.description.isNotBlank()) {
        Text(model.description, style = AppType.BodyL, color = Palette.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 460.dp))
    }
    SubLine("", model.favorite)
}

@Composable
private fun VodInfo(model: HeroModel.Vod) {
    val e = model.entry
    Text(e.title, style = AppType.Headline, color = Palette.OnBackground, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 460.dp))
    val left = (e.durationSeconds - e.positionMs / 1000).coerceAtLeast(0)
    Text("${e.channelName} · watched ${Format.agoMillis(e.watchedAtMillis)} · ${Format.clock(left)} left", style = AppType.BodyM, color = Palette.Muted, maxLines = 1)
    if (e.durationSeconds > 0) {
        Box(Modifier.padding(top = 4.dp).width(320.dp).height(4.dp).background(Palette.Track, RoundedCornerShape(2.dp))) {
            Box(Modifier.fillMaxWidth((e.positionMs / 1000f / e.durationSeconds).coerceIn(0f, 1f)).fillMaxHeight().background(Palette.Brand))
        }
    }
}

@Composable
private fun SubLine(category: String, favorite: Boolean) {
    val parts = listOfNotNull(category.takeIf { it.isNotBlank() }, "★ Favorite".takeIf { favorite })
    if (parts.isEmpty()) return
    Text(parts.joinToString("  ·  "), style = AppType.BodyM, color = if (favorite && category.isBlank()) Palette.BrandSoft else Palette.TextTertiary, maxLines = 1)
}

/** A quiet hint under a page title or in an empty hero: which keys do what. */
@Composable
fun KeyHint(text: String, modifier: Modifier = Modifier) {
    Text(text, style = AppType.BodyM, color = Palette.TextTertiary, modifier = modifier)
}
