package com.s0undplus.twitch.api

import com.s0undplus.twitch.Category
import com.s0undplus.twitch.Page
import com.s0undplus.twitch.Stream
import com.s0undplus.twitch.TwitchApiException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class BrowseTest {
    private fun stream(login: String) = Stream(userId = login, userLogin = login, userName = login)

    private val calls = mutableListOf<String>()
    private var directoryFails = false

    private val browse = Browse(
        directoryStreams = { f, game, after ->
            calls += "d ${f.sort} $game $after"
            if (directoryFails) throw DirectoryException("failed integrity check")
            Page(listOf(stream("d")), "D2")
        },
        helixStreams = { languages, game, after ->
            calls += "h $languages $game $after"
            Page(listOf(stream("h")), "H2")
        },
        directoryCategories = { tags, after ->
            calls += "dc $tags $after"
            if (directoryFails) throw TwitchApiException(500, "down")
            Page(listOf(Category("1", "A")), null)
        },
        helixCategories = { after ->
            calls += "hc $after"
            Page(listOf(Category("2", "B")), "HC2")
        },
    )

    @Test
    fun theDirectoryAnswersAndItsCursorLeadsBackToIt() = runTest {
        val first = browse.streams(StreamFilter(StreamSort.RECENT), gameId = "33")
        assertEquals("d:D2", first.nextCursor)
        browse.streams(StreamFilter(StreamSort.RECENT), gameId = "33", cursor = first.nextCursor)
        assertEquals(listOf("d RECENT 33 null", "d RECENT 33 D2"), calls)
    }

    @Test
    fun whenTheDirectoryRefusesHelixAnswersWhatItCanAndKeepsTheList() = runTest {
        directoryFails = true
        val first = browse.streams(StreamFilter(languages = setOf("en")))
        assertEquals("h", first.items.single().userLogin)
        assertEquals("h:H2", first.nextCursor)
        browse.streams(StreamFilter(languages = setOf("en")), cursor = first.nextCursor)
        assertEquals(listOf("d VIEWERS null null", "h [en] null null", "h [en] null H2"), calls)
    }

    @Test
    fun aSortOrTagsHelixCantDoFailAsTheDirectoryDid() = runTest {
        directoryFails = true
        assertFailsWith<DirectoryException> { browse.streams(StreamFilter(StreamSort.FEWEST_VIEWERS)) }
        assertFailsWith<DirectoryException> { browse.streams(StreamFilter(tags = setOf("English"))) }
        assertTrue(calls.none { it.startsWith("h ") })
    }

    @Test
    fun categoriesFallBackOnlyWithoutGameTypes() = runTest {
        directoryFails = true
        assertEquals("h:HC2", browse.categories(emptySet()).nextCursor)
        assertFailsWith<TwitchApiException> { browse.categories(setOf("shooter")) }
        assertEquals(listOf("dc [] null", "hc null", "dc [shooter] null"), calls)
    }
}
