package com.s0undplus.tv.ui.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePlaylistsTest {
    private fun strip(text: String) = String(LiveEdgePlaylistParserFactory.stripStartTag(text.toByteArray()))

    /** The head of a real Twitch live media playlist (captured 2026-10-11), which starts playback ~20 s behind live. */
    private val twitch = """
        #EXTM3U
        #EXT-X-VERSION:3
        #EXT-X-TARGETDURATION:5
        #EXT-X-MEDIA-SEQUENCE:0
        #EXT-X-TWITCH-ELAPSED-SECS:1190.000
        #EXT-X-START:TIME-OFFSET=0.000
        #EXT-X-PROGRAM-DATE-TIME:2026-10-11T14:29:09.259Z
        #EXTINF:2.000,live
        https://example.invalid/1.ts
    """.trimIndent()

    @Test
    fun theStartTagIsRemovedAndNothingElse() {
        val out = strip(twitch)
        assertFalse(out.contains("#EXT-X-START"))
        assertEquals(twitch.lines().filterNot { it.startsWith("#EXT-X-START") }, out.lines())
        assertTrue(out.contains("#EXT-X-TWITCH-ELAPSED-SECS:1190.000"))
    }

    @Test
    fun aPlaylistWithoutTheTagIsPassedThroughUntouched() {
        val bytes = "#EXTM3U\r\n#EXTINF:2.000,live\r\nseg.ts\r\n".toByteArray()
        assertArrayEquals(bytes, LiveEdgePlaylistParserFactory.stripStartTag(bytes))
    }

    @Test
    fun theTagIsRemovedWhateverItsAttributes() {
        val out = strip("#EXTM3U\n#EXT-X-START:TIME-OFFSET=-12.5,PRECISE=YES\n#EXTINF:2,\nseg.ts")
        assertEquals(listOf("#EXTM3U", "#EXTINF:2,", "seg.ts"), out.lines())
    }
}
