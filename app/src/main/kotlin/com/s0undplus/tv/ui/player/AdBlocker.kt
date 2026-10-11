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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient

/** One way of getting an ad-free copy of a stream, tried in list order and skipped for a while once it has failed. */
data class AdSource(val label: String, val options: PlaybackOptions) {
    /** Identifies this source across lists and settings changes, for remembering that it failed. */
    val key: String
        get() = when (val route = options.route) {
            is PlaybackRoute.CustomUrl -> route.template
            is PlaybackRoute.Http -> "http:${route.proxy.host}:${route.proxy.port}"
            PlaybackRoute.Direct -> "direct:${options.platform}/${options.playerType}"
        }

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
        // A fresh device id per request, as Xtra's swap does.
        AdSource(
            "${it.platform}/${it.playerType}",
            PlaybackOptions(playerType = it.playerType, platform = it.platform, freshDeviceId = true),
        )
    }
    AdMode.OFF, AdMode.HIDE -> emptyList()
}

/** The first non-audio rung of the stream: a media playlist that carries its ad markers, for polling. */
fun PlaybackStream.watchableRungUrl(): String? = (variants.firstOrNull { !it.isAudioOnly } ?: variants.firstOrNull())?.url

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
 * [alt] instead of the original while [AdState.Swapped]. While swapped it also watches the ad-free copy itself, and if
 * that turns out to carry an ad it moves on to the next source within the same break.
 *
 * Everything here runs on one thread (the composition's main dispatcher), so no locking is needed.
 */
class AdBlocker(
    private val http: OkHttpClient,
    private val liveOffsetMs: () -> Long?,
    private val resolveAlt: suspend () -> AltStream?,
    /** The ad-free source that failed, or null when a lookup was abandoned before it said which. */
    private val onAltFailed: (AdSource?) -> Unit,
) {
    var state by mutableStateOf(AdState.Idle)
        private set

    var alt by mutableStateOf<AltStream?>(null)
        private set

    private val planner = AdBreakPlanner()
    private var scope: CoroutineScope? = null
    private var swapEnabled = false
    private var prefetch: Deferred<AltStream?>? = null
    private var prefetchedAtMs = 0L
    private var swapAttempt: Deferred<AltStream?>? = null
    private var swapsThisBreak = 0

    /**
     * Runs until cancelled. [watchUrl] returns the original stream's media playlist (null = nothing to watch yet);
     * with [swap] a break is answered with an ad-free copy instead of only hiding it.
     */
    suspend fun run(watchUrl: () -> String?, swap: Boolean) {
        coroutineScope {
            planner.reset()
            scope = this
            swapEnabled = swap
            launch { pollPlaylist(watchUrl) }
            launch { tick() }
        }
    }

    /** The ad-free copy stopped working (player error or stall). */
    fun altFailed() {
        val failed = alt ?: return
        if (state != AdState.Swapped) return
        failAlt(failed)
    }

    private fun failAlt(failed: AltStream) {
        onAltFailed(failed.source)
        alt = null
        state = AdState.Hiding
        // Still inside the break: try the next source (the failed one is cooling down) instead of staying hidden.
        if (swapEnabled && planner.isActive) scope?.trySwap()
    }

    private suspend fun pollPlaylist(watchUrl: () -> String?) {
        while (currentCoroutineContext().isActive) {
            try {
                pollOriginal(watchUrl())
                pollAlt()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A malformed playlist must never take the player down: treat this round as "no answer".
            }
            delay(POLL_MS)
        }
    }

    private suspend fun pollOriginal(url: String?) {
        if (url == null) return
        val requestedAt = SystemClock.elapsedRealtime()
        val view = MediaPlaylistFetcher.view(http, url) ?: return
        val adAtEdge = AdBreakDetector.isAdAtEdge(view)
        val now = SystemClock.elapsedRealtime()
        // Entering is dated from when the request went out (the playlist can only have been older than that), so a slow
        // answer doesn't delay the hide; leaving is dated from when the answer arrived, where being late is the safe side.
        planner.onObservation(if (adAtEdge) requestedAt else now, adAtEdge, liveOffsetMs())
        // Resolve the ad-free copy as soon as the ad shows up at the live edge, so it is ready when it arrives.
        if (prefetch != null && !planner.isActive && now - prefetchedAtMs > PREFETCH_MAX_AGE_MS) dropPrefetch()
        if (adAtEdge && swapEnabled && prefetch == null && !planner.isActive && !planner.isLockedOut) {
            scope?.startPrefetch(now)
        }
    }

    /** While swapped, make sure the ad-free copy is not itself carrying an ad. */
    private suspend fun pollAlt() {
        val current = alt ?: return
        if (state != AdState.Swapped) return
        val url = current.stream.watchableRungUrl() ?: return
        val view = MediaPlaylistFetcher.view(http, url) ?: return
        if (alt === current && AdBreakDetector.isAdAtEdge(view)) failAlt(current)
    }

    private suspend fun tick() {
        try {
            while (currentCoroutineContext().isActive) {
                when (planner.poll(SystemClock.elapsedRealtime())) {
                    AdBreakPlanner.Action.Enter -> scope?.enter()
                    AdBreakPlanner.Action.Exit -> exit()
                    AdBreakPlanner.Action.None -> Unit
                }
                delay(TICK_MS)
            }
        } finally {
            // Cancelled (screen closed, mode changed, app stopped): never leave the picture hidden or swapped behind.
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

    private fun CoroutineScope.enter() {
        state = AdState.Hiding
        swapsThisBreak = 0
        if (swapEnabled) trySwap()
    }

    /** Swap to the next usable ad-free copy if one can be had in time; otherwise the ad just stays hidden. */
    private fun CoroutineScope.trySwap() {
        if (swapsThisBreak >= MAX_SWAPS_PER_BREAK) return
        swapsThisBreak++
        val pending = prefetch ?: safeResolve()
        prefetch = null // consumed: a later failover resolves afresh
        swapAttempt?.cancel()
        swapAttempt = pending
        launch {
            val resolved = withTimeoutOrNull(SWAP_WAIT_MS) { pending.await() }
            // The break may have ended while we waited; only swap if the viewer is still looking at the ad.
            if (state == AdState.Hiding && planner.isActive && swapAttempt === pending) {
                if (resolved != null) {
                    alt = resolved
                    state = AdState.Swapped
                } else if (!pending.isCompleted) {
                    // Timed out while a source was still being tried: give up on it once (a lookup that failed on its
                    // own has already marked its source).
                    pending.cancel()
                    onAltFailed(null)
                }
            }
        }
    }

    private fun exit() {
        dropPrefetch()
        swapAttempt?.cancel()
        swapAttempt = null
        alt = null
        state = AdState.Idle
    }

    private companion object {
        /** A media playlist for live low-latency is a few hundred bytes of text; this is cheap and bounds ad-leak time. */
        const val POLL_MS = 2_000L
        const val TICK_MS = 250L
        const val SWAP_WAIT_MS = 5_000L
        const val PREFETCH_MAX_AGE_MS = 120_000L

        /** A break whose ad-free copies keep failing is hidden instead of retried forever. */
        const val MAX_SWAPS_PER_BREAK = 3
    }
}
