package com.s0undplus.tv.ui.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.upstream.ParsingLoadable
import java.io.InputStream

/**
 * How far behind the newest segment a live stream plays. Twitch's segments are 2 s long, so this is about three of them:
 * close enough that chat matches the picture, with enough in hand to ride out a slow segment.
 */
internal const val LIVE_TARGET_OFFSET_MS = 6_000L

/** Live items ask ExoPlayer to hold [LIVE_TARGET_OFFSET_MS] behind live instead of what the playlist implies. */
@OptIn(UnstableApi::class)
internal fun liveConfiguration(): MediaItem.LiveConfiguration =
    MediaItem.LiveConfiguration.Builder()
        .setTargetOffsetMs(LIVE_TARGET_OFFSET_MS)
        .setMinOffsetMs(3_000)
        .setMaxOffsetMs(20_000)
        .build()

/**
 * Parses HLS playlists with Media3's own parser after dropping `#EXT-X-START`.
 *
 * Twitch's live media playlists say `#EXT-X-START:TIME-OFFSET=0.000`, which tells the player to start at the *oldest*
 * segment in the window and (in Media3) to keep that whole window, around 20 s, between the viewer and live. Without
 * the tag, the target set by [liveConfiguration] decides where playback starts and how far behind it stays.
 * VODs don't use the tag for anything we need, so removing it everywhere is harmless.
 */
@OptIn(UnstableApi::class)
internal class LiveEdgePlaylistParserFactory(
    private val delegate: HlsPlaylistParserFactory = DefaultHlsPlaylistParserFactory(),
) : HlsPlaylistParserFactory {
    override fun createPlaylistParser(): ParsingLoadable.Parser<HlsPlaylist> = withoutStart(delegate.createPlaylistParser())

    override fun createPlaylistParser(
        multivariantPlaylist: HlsMultivariantPlaylist,
        previousMediaPlaylist: HlsMediaPlaylist?,
    ): ParsingLoadable.Parser<HlsPlaylist> =
        withoutStart(delegate.createPlaylistParser(multivariantPlaylist, previousMediaPlaylist))

    private fun withoutStart(parser: ParsingLoadable.Parser<HlsPlaylist>) =
        ParsingLoadable.Parser<HlsPlaylist> { uri: Uri, input: InputStream ->
            parser.parse(uri, stripStartTag(input.readBytes()).inputStream())
        }

    companion object {
        private val START_TAG = "#EXT-X-START".toByteArray()

        /** The playlist with every line that begins with `#EXT-X-START` removed; other bytes are untouched. */
        fun stripStartTag(bytes: ByteArray): ByteArray {
            if (!contains(bytes, START_TAG)) return bytes
            val text = String(bytes, Charsets.UTF_8)
            return text.lineSequence()
                .filterNot { it.trimStart().startsWith("#EXT-X-START") }
                .joinToString("\n")
                .toByteArray(Charsets.UTF_8)
        }

        private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
            outer@ for (i in 0..haystack.size - needle.size) {
                for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
                return true
            }
            return false
        }
    }
}
