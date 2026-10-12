package com.s0undplus.twitch.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SeekPreviewsTest {
    /** The shape Twitch serves at a video's seekPreviewsURL: one entry per quality, image names relative to it. */
    private val info = """
        [
          {"count":250,"width":220,"rows":10,"images":["abc-low-0.jpg","abc-low-1.jpg","abc-low-2.jpg"],"interval":10,"quality":"low","cols":10,"height":124},
          {"count":250,"width":320,"rows":5,"images":["abc-high-0.jpg","abc-high-1.jpg","abc-high-2.jpg","abc-high-3.jpg","abc-high-4.jpg","abc-high-5.jpg","abc-high-6.jpg","abc-high-7.jpg","abc-high-8.jpg","abc-high-9.jpg"],"interval":10.5,"quality":"high","cols":5,"height":180}
        ]
    """.trimIndent()
    private val infoUrl = "https://d2nvs31859zcd8.cloudfront.net/abc_123/storyboards/987-info.json"

    @Test
    fun parsingPicksTheSharpestQualityAndResolvesImageNames() {
        val previews = assertNotNull(SeekPreviews.parse(info, infoUrl))
        assertEquals(320, previews.width)
        assertEquals(180, previews.height)
        assertEquals(10.5, previews.intervalSeconds)
        assertEquals("https://d2nvs31859zcd8.cloudfront.net/abc_123/storyboards/abc-high-0.jpg", previews.images.first())
        assertEquals(10, previews.images.size)
    }

    @Test
    fun aMomentMapsToItsTileOnTheRightSheet() {
        val previews = assertNotNull(SeekPreviews.parse(info, infoUrl))
        // 25 frames per sheet (5 x 5). Frame 0 is the top-left tile of the first sheet.
        assertEquals(SeekPreviewFrame(previews.images[0], 0, 0, 320, 180), previews.frameAt(0.0))
        // 10.5 s per frame: 100 s is frame 9, the last tile of the second row.
        assertEquals(SeekPreviewFrame(previews.images[0], 4 * 320, 1 * 180, 320, 180), previews.frameAt(100.0))
        // Frame 26 is the second tile of the second sheet.
        assertEquals(SeekPreviewFrame(previews.images[1], 1 * 320, 0, 320, 180), previews.frameAt(26 * 10.5 + 1))
    }

    @Test
    fun timesOutsideTheVideoAreClampedToItsFirstAndLastFrames() {
        val previews = assertNotNull(SeekPreviews.parse(info, infoUrl))
        assertEquals(previews.frameAt(0.0), previews.frameAt(-30.0))
        val last = assertNotNull(previews.frameAt(1e9))
        // Frame 249: sheet 9, tile 24 (bottom-right).
        assertEquals(SeekPreviewFrame(previews.images[9], 4 * 320, 4 * 180, 320, 180), last)
    }

    @Test
    fun brokenOrEmptyDescriptionsMeanNoPreviews() {
        assertNull(SeekPreviews.parse("{}", infoUrl))
        assertNull(SeekPreviews.parse("[]", infoUrl))
        assertNull(SeekPreviews.parse("""[{"quality":"high","images":[]}]""", infoUrl))
        assertNull(SeekPreviews.parse(info, "not a url"))
    }

    @Test
    fun aSheetListShorterThanTheFrameCountGivesNoFrameRatherThanAWrongOne() {
        val short = SeekPreviews(count = 100, width = 10, height = 10, rows = 2, cols = 2, intervalSeconds = 1.0, images = listOf("a", "b"))
        assertNotNull(short.frameAt(7.0)) // frame 7: sheet 1
        assertNull(short.frameAt(8.0)) // frame 8: sheet 2, which doesn't exist
    }
}
