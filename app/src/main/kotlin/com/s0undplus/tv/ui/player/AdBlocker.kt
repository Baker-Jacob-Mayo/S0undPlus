package com.s0undplus.tv.ui.player

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.s0undplus.tv.data.AdMode
import com.s0undplus.tv.data.AppSettings
import com.s0undplus.twitch.playback.AdBreakDetector
import com.s0undplus.twitch.playback.AdBreakPlanner
import com.s0undplus.twitch.playback.MediaPlaylistFetcher
import com.s0undplus.twitch.playback.PlaybackOptions
import com.s0undplus.twitch.playback.PlaybackRoute
import com.s0undplus.twitch.playback.PlaybackStream
import com.s0undplus.twitch.playback.clientFor
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient

/** One way of getting an ad-free copy of a stream, tried in list order and skipped once it has failed. */
data class AdSource(val label: String, val options: PlaybackOptions) {
    /**
     * The client for the player's own media-playlist requests, only when this source routes those through an HTTP
     * proxy; null means the player's normal networking is fine.
     */
    fun mediaClient(base: OkHttpClient): OkHttpClient? =
        (options.route as? PlaybackRoute.Http)?.takeIf { it.proxy.proxyMedia }?.clientFor(base)
}

/** An ad-free copy that has been resolved and is ready to be played in place of the original during a break. */
data class AltStream(val source: AdSource, val stream: PlaybackStream)

/** The sources the current settings call for, in the order they are tried. Empty for modes that need none. */
fun AppSettings.adSources(): List<AdSource> = when (adMode) {
    AdMode.PROXY -> proxyEntries.filter { it.enabled }.map {
        AdSource(
            label = it.template.substringAfter("://").substringBefore('/'),
            options = PlaybackOptions(PlaybackRoute.CustomUrl(it.template), playerType = effectivePlayerType),
        )
    }
    AdMode.HTTP_PROXY -> listOfNotNull(
        httpProxy?.let { AdSource(it.host, PlaybackOptions(PlaybackRoute.Http(it), playerType = effectivePlayerType)) },
    )
    AdMode.VIDEO_SWAP -> swapEntries.filter { it.enabled }.map {
        AdSource("${it.platform}/${it.playerType}", PlaybackOptions(playerType = it.playerType, platform = it.platform))
    }
    AdMode.OFF, AdMode.HIDE -> emptyList()
}

enum class AdState {
    /** No ad on screen: normal playback. */
    Idle,

    /** The viewer has reached an ad on the original stream: muted and blacked out. */
    Hiding,

    /** The player is showing [AdBlocker.alt] instead of the ad. */
    Swapped,
}

/**
 * Hides or replaces Twitch's stitched ads on a live stream, the way Xtra does.
 *
 * It watches the *original* stream's media playlist (polled as text, so it keeps working while the player is on a
 * different stream), asks [AdBreakPlanner] when the viewer's own position reaches and leaves an ad, and exposes the
 * result as [state] / [alt] for the player screen to act on: mute and black out while [AdState.Hiding], play
 * [alt] instead of the original while [AdState.Swapped].
 *
 * Everything here runs on one thread (the composition's main dispatcher), so no locking is needed.
 */
class AdBlocker(
    private val http: OkHttpClient,
    private val liveOffsetMs: () -> Long?,
    private val resolveAlt: suspend () -> AltStream?,
    private val onAltFailed: () -> Unit,
) {
    var state by mutableStateOf(AdState.Idle)
        private set

    var alt by mutableStateOf<AltStream?>(null)
        private set

    private val planner = AdBreakPlanner()
    private var prefetch: Deferred<AltStream?>? = null
    private var prefetchedAtMs = 0L

    /**
     * Runs until cancelled. [watchUrl] returns the original stream's media playlist (null = nothing to watch yet);
     * with [swap] a break is answered with an ad-free copy instead of only hiding it.
     */
    suspend fun run(watchUrl: () -> String?, swap: Boolean) {
        coroutineScope {
            planner.reset()
            launch { pollPlaylist(watchUrl, swap) }
            launch { tick(swap) }
        }
    }

    /** The ad-free copy stopped working mid-break: go back to the original, hidden, and skip that source next time. */
    fun altFailed() {
        if (state != AdState.Swapped) return
        onAltFailed()
        alt = null
        state = AdState.Hiding
    }

    private suspend fun CoroutineScope.pollPlaylist(watchUrl: () -> String?, swap: Boolean) {
        while (isActive) {
            val url = watchUrl()
            val view = if (url != null) MediaPlaylistFetcher.view(http, url) else null
            if (view != null) {
                val now = SystemClock.elapsedRealtime()
                val adAtEdge = AdBreakDetector.isAdAtEdge(view)
                // A copy prepared for an ad that never reached the viewer would be stale by the next one.
                if (prefetch != null && !planner.isActive && now - prefetchedAtMs > PREFETCH_MAX_AGE_MS) dropPrefetch()
                // Resolve the ad-free copy as soon as the ad shows up at the live edge, so it is ready when it arrives.
                if (adAtEdge && swap && prefetch == null && !planner.isActive) startPrefetch(now)
                planner.onObservation(now, adAtEdge, liveOffsetMs())
            }
            delay(POLL_MS)
        }
    }

    private suspend fun CoroutineScope.tick(swap: Boolean) {
        try {
            while (isActive) {
                when (planner.poll(SystemClock.elapsedRealtime())) {
                    AdBreakPlanner.Action.Enter -> enter(swap)
                    AdBreakPlanner.Action.Exit -> exit()
                    AdBreakPlanner.Action.None -> Unit
                }
                delay(TICK_MS)
            }
        } finally {
            // Cancelled (screen closed, mode changed): never leave the picture hidden or swapped behind.
            exit()
        }
    }

    private fun CoroutineScope.safeResolve(): Deferred<AltStream?> = async {
        try {
            resolveAlt()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null // a failed lookup must end this break's swap, never take the whole player down
        }
    }

    private fun CoroutineScope.startPrefetch(now: Long) {
        prefetch = safeResolve()
        prefetchedAtMs = now
    }

    private fun dropPrefetch() {
        prefetch?.cancel()
        prefetch = null
    }

    private fun CoroutineScope.enter(swap: Boolean) {
        state = AdState.Hiding
        if (!swap) return
        val pending = prefetch ?: safeResolve().also {
            prefetch = it
            prefetchedAtMs = SystemClock.elapsedRealtime()
        }
        launch {
            val resolved = withTimeoutOrNull(SWAP_WAIT_MS) { pending.await() }
            // The break may have ended while we waited; only swap if the viewer is still looking at the ad.
            if (state == AdState.Hiding && planner.isActive) {
                if (resolved != null) {
                    alt = resolved
                    state = AdState.Swapped
                } else {
                    onAltFailed()
                }
            }
        }
    }

    private fun exit() {
        dropPrefetch()
        alt = null
        state = AdState.Idle
    }

    private companion object {
        /** A media playlist for live low-latency is a few hundred bytes of text; this is cheap and bounds ad-leak time. */
        const val POLL_MS = 2_000L
        const val TICK_MS = 250L
        const val SWAP_WAIT_MS = 5_000L
        const val PREFETCH_MAX_AGE_MS = 120_000L
    }
}
