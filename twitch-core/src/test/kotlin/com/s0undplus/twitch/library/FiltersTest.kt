package com.s0undplus.twitch.library

import com.s0undplus.twitch.api.StreamFilter
import com.s0undplus.twitch.api.StreamSort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FiltersTest {
    @Test
    fun aFilterSurvivesARoundTripAndBadDataReadsAsNotSet() {
        val filter = StreamFilter(StreamSort.RECENT, setOf("en", "da"), setOf("Chill"))
        assertEquals(filter, FilterOps.decodeFilter(FilterOps.encodeFilter(filter)))
        assertNull(FilterOps.decodeFilter(null))
        assertNull(FilterOps.decodeFilter("{not json"))
        // A sort this version doesn't know (from a newer one) falls back to the default rather than losing the rest.
        assertEquals(StreamFilter(languages = setOf("en")), FilterOps.decodeFilter("""{"sort":"SOMEDAY","languages":["en"]}"""))
    }

    @Test
    fun tagIdsSurviveARoundTrip() {
        assertEquals(setOf("a", "b"), FilterOps.decodeIds(FilterOps.encodeIds(setOf("a", "b"))))
        assertTrue(FilterOps.decodeIds("[oops").isEmpty())
    }

    @Test
    fun categoryFiltersKeepTheMostRecentAndForgetDefaultsAndTheOldest() {
        var list = emptyList<CategoryFilter>()
        repeat(FilterOps.MAX_CATEGORY_FILTERS + 2) { i -> list = FilterOps.withCategoryFilter(list, "c$i", StreamFilter(StreamSort.RECENT)) }
        assertEquals(FilterOps.MAX_CATEGORY_FILTERS, list.size)
        assertEquals("c2", list.first().id)

        // Changing one moves it to the end; setting it back to the default forgets it.
        list = FilterOps.withCategoryFilter(list, "c2", StreamFilter(StreamSort.FEWEST_VIEWERS))
        assertEquals(CategoryFilter("c2", StreamFilter(StreamSort.FEWEST_VIEWERS)), list.last())
        list = FilterOps.withCategoryFilter(list, "c2", StreamFilter())
        assertTrue(list.none { it.id == "c2" })
        assertEquals(list, FilterOps.decodeCategoryFilters(FilterOps.encodeCategoryFilters(list)))
    }
}
