package com.s0undplus.tv.fake

import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import mockwebserver3.MockResponse
import okio.Buffer
import org.junit.rules.ExternalResource

/**
 * A live HLS channel served from the fake Twitch: a playlist that slides forward in real time over a handful of tiny
 * real video segments (made with ffmpeg, in the test APK's assets), plus the token and master-playlist requests that
 * lead to it.
 *
 * Two copies exist, as with Twitch: "live" (what the player starts on, and which can be given a stitched ad break
 * with [startAd] / [endAd]) and "alt" (the ad-free copy, asked for with the Android platform, which never has ads).
 * Ad segments are a different clip (solid red) so a screenshot shows which one is on screen. The ad markers are the
 * ones Xtra documents (a segment title, and a stitched-ad DATERANGE); they are synthetic, not captured from Twitch.
 */
object FakeStreams {
    private const val SEGMENT_MS = 2_000L
    private const val WINDOW_SEGMENTS = 4
    private const val CONTENT_CLIPS = 3
    private const val AD_CLIP = -1

    @Volatile private var startMs = 0L
    @Volatile private var adFromSeq: Int? = null
    @Volatile private var adUntilSeq: Int? = null
    private val clips = ConcurrentHashMap<String, ByteArray>()

    /** "live/12" or "alt/12" for every segment the player has fetched, oldest first. */
    val segmentRequests = CopyOnWriteArrayList<String>()

    /** Media-playlist requests as "live exoplayer" / "live okhttp": the player and the ad blocker both poll them. */
    val playlistRequests = CopyOnWriteArrayList<String>()

    /** Master-playlist requests as "channel platform", e.g. "alpha android". */
    val masterRequests = CopyOnWriteArrayList<String>()

    fun install() {
        startMs = System.currentTimeMillis()
        adFromSeq = null
        adUntilSeq = null
        segmentRequests.clear()
        masterRequests.clear()
        playlistRequests.clear()
        FakeTwitch.on("/gql") {
            FakeTwitch.json("""{"data":{"streamPlaybackAccessToken":{"value":"{\"fake\":true}","signature":"fake-signature"}}}""")
        }
        FakeTwitch.on("/usher/api/v2/channel/hls/") { request ->
            val channel = request.url.encodedPath.substringAfterLast('/').removeSuffix(".m3u8")
            val platform = request.url.queryParameter("platform").orEmpty()
            masterRequests += "$channel $platform"
            FakeTwitch.text(master(if (platform == "android") "alt" else "live"), contentType = MPEGURL)
        }
        FakeTwitch.on("/hls/live/media.m3u8") { request ->
            playlistRequests += "live ${who(request.headers["Accept-Encoding"])}"
            FakeTwitch.text(mediaPlaylist("live"), contentType = MPEGURL)
        }
        FakeTwitch.on("/hls/alt/media.m3u8") { request ->
            playlistRequests += "alt ${who(request.headers["Accept-Encoding"])}"
            FakeTwitch.text(mediaPlaylist("alt"), contentType = MPEGURL)
        }
        FakeTwitch.on("/hls/seg/") { request ->
            val parts = request.url.encodedPath.removePrefix("/hls/seg/").removeSuffix(".ts").split('/')
            val kind = parts[0]
            val seq = parts[1].toInt()
            segmentRequests += "$kind/$seq"
            segment(kind, seq)
        }
    }

    /** ExoPlayer's data source asks for `identity` encoding; the app's own OkHttp calls leave the default (gzip). */
    private fun who(acceptEncoding: String?): String = if (acceptEncoding == "identity") "exoplayer" else "app-okhttp"

    /** The newest segment the playlist offers right now. */
    fun latestSeq(): Int = 3 + ((System.currentTimeMillis() - startMs) / SEGMENT_MS).toInt()

    /** From the next segment on, the live copy carries an ad break (until [endAd]). */
    fun startAd() {
        adUntilSeq = null
        adFromSeq = latestSeq() + 1
    }

    /** From the next segment on, the live copy is ordinary content again. */
    fun endAd() {
        adUntilSeq = latestSeq() + 1
    }

    private fun isAd(kind: String, seq: Int): Boolean {
        if (kind != "live") return false
        val from = adFromSeq ?: return false
        val until = adUntilSeq
        return seq >= from && (until == null || seq < until)
    }

    private fun clipOf(kind: String, seq: Int): Int = if (isAd(kind, seq)) AD_CLIP else seq % CONTENT_CLIPS

    /** Each ad segment is the same clip again and a content cycle restarts its clock, so those need a discontinuity. */
    private fun discontinuityBefore(kind: String, seq: Int): Boolean {
        if (seq == 0) return true
        val clip = clipOf(kind, seq)
        val previous = clipOf(kind, seq - 1)
        return clip == AD_CLIP || previous == AD_CLIP || clip != previous + 1
    }

    private fun time(seq: Int): String = Instant.ofEpochMilli(startMs + (seq - 4) * SEGMENT_MS).toString()

    private fun master(kind: String): String = """
        #EXTM3U
        #EXT-X-MEDIA:TYPE=VIDEO,GROUP-ID="chunked",NAME="180p (source)",AUTOSELECT=YES,DEFAULT=YES
        #EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=150000,RESOLUTION=320x180,CODECS="avc1.42C01E,mp4a.40.2",VIDEO="chunked",FRAME-RATE=15.000
        ${FakeTwitch.baseUrl}/hls/$kind/media.m3u8
    """.trimIndent() + "\n"

    private fun mediaPlaylist(kind: String): String {
        val latest = latestSeq()
        val first = latest - (WINDOW_SEGMENTS - 1)
        val discontinuitiesBefore = (0 until first).count { discontinuityBefore(kind, it) }
        val breakStart = adFromSeq
        val text = StringBuilder()
        text.append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n")
        text.append("#EXT-X-MEDIA-SEQUENCE:$first\n#EXT-X-DISCONTINUITY-SEQUENCE:$discontinuitiesBefore\n")
        if (kind == "live" && breakStart != null && (first..latest).any { isAd(kind, it) }) {
            text.append("""#EXT-X-DATERANGE:ID="stitched-ad-$breakStart",CLASS="twitch-stitched-ad",START-DATE="${time(breakStart)}",DURATION=30.0""")
            text.append('\n')
        }
        for (seq in first..latest) {
            if (discontinuityBefore(kind, seq)) text.append("#EXT-X-DISCONTINUITY\n")
            text.append("#EXT-X-PROGRAM-DATE-TIME:${time(seq)}\n")
            text.append("#EXTINF:2.000,${if (isAd(kind, seq)) "Amazon|$seq" else "live"}\n")
            text.append("${FakeTwitch.baseUrl}/hls/seg/$kind/$seq.ts\n")
        }
        return text.toString()
    }

    private fun segment(kind: String, seq: Int): MockResponse {
        val clip = clipOf(kind, seq)
        val name = if (clip == AD_CLIP) "ad.ts" else "content$clip.ts"
        val bytes = clips.getOrPut(name) {
            InstrumentationRegistry.getInstrumentation().context.assets.open("hls/$name").use { it.readBytes() }
        }
        return MockResponse.Builder().code(200).addHeader("Content-Type", "video/mp2t").body(Buffer().write(bytes)).build()
    }

    private const val MPEGURL = "application/vnd.apple.mpegurl"
}

/** Installs [FakeStreams] before the activity starts (declare it right after the hermetic rule). */
class StreamsRule : ExternalResource() {
    override fun before() = FakeStreams.install()
}
