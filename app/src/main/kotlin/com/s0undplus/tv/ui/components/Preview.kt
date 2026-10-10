package com.s0undplus.tv.ui.components

import android.content.Context
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.s0undplus.tv.AppContainer
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Plays a muted, lowest-quality live preview inside whichever card is focused on Home.
 *
 * Many Google TV chips have only one or two hardware video decoders, so there is exactly **one** preview player, it
 * only runs while a card is focused, it picks the smallest video rung, and it is released when Home goes away (before
 * the real player needs the decoder).
 */
@OptIn(UnstableApi::class)
class PreviewController(
    private val container: AppContainer,
    context: Context,
    private val scope: CoroutineScope,
) {
    val player: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        volume = 0f
        playWhenReady = true
    }

    /** The channel a preview is being loaded/played for. */
    var activeLogin by mutableStateOf<String?>(null)
        private set

    /** The channel whose first frame is on screen (so the thumbnail can be hidden behind it). */
    var readyLogin by mutableStateOf<String?>(null)
        private set

    private var job: Job? = null

    private val listener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            readyLogin = activeLogin
        }
    }

    init {
        player.addListener(listener)
    }

    fun start(login: String) {
        if (activeLogin == login) return
        stop()
        activeLogin = login
        job = scope.launch {
            try {
                val stream = container.playback.live(login)
                val rung = stream.variants.filter { !it.isAudioOnly }.minByOrNull { it.bandwidth } ?: return@launch
                player.setMediaItem(MediaItem.Builder().setUri(rung.url).setMimeType(MimeTypes.APPLICATION_M3U8).build())
                player.prepare()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // offline or unavailable: just keep showing the thumbnail
                activeLogin = null
            }
        }
    }

    /** Stops the preview; if [login] is given, only when that channel is the one currently previewed. */
    fun stop(login: String? = null) {
        if (login != null && activeLogin != login) return
        job?.cancel()
        player.stop()
        player.clearMediaItems()
        activeLogin = null
        readyLogin = null
    }

    fun release() {
        job?.cancel()
        player.removeListener(listener)
        player.release()
    }
}

/** Overlay for a focused card's thumbnail: waits a moment (so scrolling past doesn't spawn requests), then previews. */
@OptIn(UnstableApi::class)
@Composable
fun PreviewLayer(controller: PreviewController, login: String) {
    LaunchedEffect(login) {
        delay(PREVIEW_DELAY_MS)
        controller.start(login)
    }
    DisposableEffect(login) { onDispose { controller.stop(login) } }

    if (controller.activeLogin == login) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    isFocusable = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    player = controller.player
                }
            },
            modifier = Modifier.fillMaxSize().alpha(if (controller.readyLogin == login) 1f else 0f),
        )
    }
}

private const val PREVIEW_DELAY_MS = 1_200L
