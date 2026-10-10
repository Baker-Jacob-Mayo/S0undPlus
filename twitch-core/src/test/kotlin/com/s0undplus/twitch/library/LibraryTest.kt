package com.s0undplus.twitch.library

import com.s0undplus.twitch.TwitchLanguages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibraryTest {
    private fun fav(login: String) = FavoriteChannel(login, login.replaceFirstChar { it.uppercase() })
    private fun entry(id: String, at: Long = 0) = HistoryEntry(id, "t$id", "Chan", "chan", 3600, "thumb", 5_000, at)

    @Test
    fun togglingAddsThenRemovesCaseInsensitively() {
        val one = LibraryOps.toggleFavorite(emptyList(), fav("alice"))
        assertEquals(listOf("alice"), one.map { it.login })
        assertTrue(LibraryOps.isFavorite(one, "ALICE"))
        val two = LibraryOps.toggleFavorite(one, fav("Alice"))
        assertTrue(two.isEmpty())
    }

    @Test
    fun favoritesAreBounded() {
        var list = emptyList<FavoriteChannel>()
        repeat(LibraryOps.MAX_FAVORITES + 10) { list = LibraryOps.toggleFavorite(list, fav("c$it")) }
        assertEquals(LibraryOps.MAX_FAVORITES, list.size)
        assertEquals("c${LibraryOps.MAX_FAVORITES + 9}", list.last().login) // newest kept, oldest dropped
    }

    @Test
    fun historyIsNewestFirstDeduplicatedAndBounded() {
        var h = emptyList<HistoryEntry>()
        h = LibraryOps.record(h, entry("1"))
        h = LibraryOps.record(h, entry("2"))
        h = LibraryOps.record(h, entry("1")) // re-watching moves it to the front, no duplicate
        assertEquals(listOf("1", "2"), h.map { it.videoId })
        repeat(LibraryOps.MAX_HISTORY + 5) { h = LibraryOps.record(h, entry("v$it")) }
        assertEquals(LibraryOps.MAX_HISTORY, h.size)
        assertEquals("v${LibraryOps.MAX_HISTORY + 4}", h.first().videoId)
        assertFalse(h.any { it.videoId == "1" })
        assertEquals(h.size - 1, LibraryOps.remove(h, h.first().videoId).size)
    }

    @Test
    fun roundTripsThroughJsonAndToleratesCorruption() {
        val favs = listOf(fav("a"), FavoriteChannel("b", "B", "42"))
        assertEquals(favs, LibraryOps.decodeFavorites(LibraryOps.encodeFavorites(favs)))
        val hist = listOf(entry("1", 99))
        assertEquals(hist, LibraryOps.decodeHistory(LibraryOps.encodeHistory(hist)))
        assertEquals(emptyList(), LibraryOps.decodeFavorites(null))
        assertEquals(emptyList(), LibraryOps.decodeFavorites("{not json"))
        assertEquals(emptyList(), LibraryOps.decodeHistory(""))
    }

    @Test
    fun languageHelpers() {
        assertEquals("English", TwitchLanguages.name("en"))
        assertEquals("zz", TwitchLanguages.name("zz"))
        assertEquals("de", TwitchLanguages.defaultFor("DE"))
        assertEquals("en", TwitchLanguages.defaultFor("xx"))
        assertEquals("es", TwitchLanguages.next("en"))
        assertEquals("en", TwitchLanguages.next(TwitchLanguages.ALL.last().first)) // wraps
    }
}
