package com.s0undplus.twitch.playback

import com.s0undplus.twitch.parseTwitchDuration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MasterPlaylistTest {
    // Shape of a real usher master playlist (URLs shortened).
    private val sample = """
        #EXTM3U
        #EXT-X-TWITCH-INFO:NODE="video-edge-1.sfo03",MANIFEST-NODE-TYPE="weaver_cluster"
        #EXT-X-MEDIA:TYPE=VIDEO,GROUP-ID="chunked",NAME="1080p60 (source)",AUTOSELECT=YES,DEFAULT=YES
        #EXT-X-STREAM-INF:BANDWIDTH=8446000,RESOLUTION=1920x1080,CODECS="avc1.64002A,mp4a.40.2",VIDEO="chunked",FRAME-RATE=60.000
        https://edge.example/v1/playlist/chunked.m3u8
        #EXT-X-MEDIA:TYPE=VIDEO,GROUP-ID="720p60",NAME="720p60",AUTOSELECT=YES,DEFAULT=YES
        #EXT-X-STREAM-INF:BANDWIDTH=3422999,RESOLUTION=1280x720,CODECS="avc1.4D401F,mp4a.40.2",VIDEO="720p60",FRAME-RATE=60.000
        https://edge.example/v1/playlist/720p60.m3u8
        #EXT-X-MEDIA:TYPE=VIDEO,GROUP-ID="audio_only",NAME="audio_only",AUTOSELECT=NO,DEFAULT=NO
        #EXT-X-STREAM-INF:BANDWIDTH=160000,CODECS="mp4a.40.2",VIDEO="audio_only"
        https://edge.example/v1/playlist/audio_only.m3u8
    """.trimIndent()

    @Test
    fun parsesAllRungsInOrder() {
        val variants = MasterPlaylist.parse(sample)
        assertEquals(listOf("1080p60 (source)", "720p60", "Audio only"), variants.map { it.name })

        val source = variants[0]
        assertEquals("https://edge.example/v1/playlist/chunked.m3u8", source.url)
        assertEquals(8_446_000, source.bandwidth)
        assertEquals(1920, source.width)
        assertEquals(1080, source.height)
        assertEquals(60.0, source.frameRate)
        assertEquals("avc1.64002A,mp4a.40.2", source.codecs) // quoted value containing a comma
        assertTrue(source.isSource)
        assertFalse(source.isAudioOnly)
    }

    @Test
    fun recognizesAudioOnly() {
        val audio = MasterPlaylist.parse(sample).last()
        assertTrue(audio.isAudioOnly)
        assertEquals(0, audio.height)
    }

    @Test
    fun emptyOrGarbageInputYieldsNoVariants() {
        assertEquals(emptyList(), MasterPlaylist.parse(""))
        assertEquals(emptyList(), MasterPlaylist.parse("<html>nope</html>"))
    }

    @Test
    fun attributeParserHandlesQuotesAndCommas() {
        val attrs = MasterPlaylist.parseAttributes("A=1,B=\"x,y\",C=z")
        assertEquals(mapOf("A" to "1", "B" to "x,y", "C" to "z"), attrs)
    }

    @Test
    fun parsesTwitchDurations() {
        assertEquals(3 * 3600 + 2 * 60 + 1L, parseTwitchDuration("3h2m1s"))
        assertEquals(45L, parseTwitchDuration("45s"))
        assertEquals(600L, parseTwitchDuration("10m"))
        assertEquals(0L, parseTwitchDuration("garbage"))
    }

    /** Twitch's newer IVS-style master playlist (shape captured from a live 1080p50 channel on 2026-10-11). */
    @Test
    fun ivsPlaylistsAreNamedWithFrameRateAndTheSourceIsFlagged() {
        val text = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=630000,RESOLUTION=640x360,CODECS="avc1.4D401E,mp4a.40.2",FRAME-RATE=30.000,STABLE-VARIANT-ID="360p30",IVS-NAME="360p",IVS-VARIANT-SOURCE="transcode"
            https://example.invalid/360.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=6745666,RESOLUTION=1920x1080,CODECS="avc1.64002A,mp4a.40.2",FRAME-RATE=50.000,STABLE-VARIANT-ID="1080p50",IVS-NAME="1080p50",IVS-VARIANT-SOURCE="source"
            https://example.invalid/1080.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=3422999,RESOLUTION=1280x720,CODECS="avc1.4D401F,mp4a.40.2",FRAME-RATE=60.000,STABLE-VARIANT-ID="720p60",IVS-NAME="720p60",IVS-VARIANT-SOURCE="transcode"
            https://example.invalid/720.m3u8
        """.trimIndent()
        val variants = MasterPlaylist.parse(text)
        assertEquals(listOf("360p", "1080p50 (source)", "720p60"), variants.map { it.name })
        assertTrue(variants[1].isSource)
        assertEquals(50.0, variants[1].frameRate)
    }

    @Test
    fun withoutAnyNameTheFrameRateIsPartOfTheFallbackName() {
        val text = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1,RESOLUTION=1280x720,FRAME-RATE=59.940\nv.m3u8"
        assertEquals("720p60", MasterPlaylist.parse(text).single().name)
    }
}