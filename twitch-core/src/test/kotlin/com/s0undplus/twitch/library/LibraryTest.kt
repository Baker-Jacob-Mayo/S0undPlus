package com.s0undplus.twitch.library

import com.s0undplus.twitch.TwitchLanguages
import com.s0undplus.twitch.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
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
    fun aRenamedChannelIsStillTheSameFollowByItsId() {
        val follows = listOf(AppFollow("oldname", "OldName", "42"), AppFollow("bob", "Bob"))
        // The channel page of the renamed channel finds the follow, and its Follow button removes it.
        assertTrue(LibraryOps.isFollowed(follows, "newname", "42"))
        assertEquals(listOf(AppFollow("bob", "Bob")), LibraryOps.toggleFollow(follows, AppFollow("newname", "NewName", "42")))
        // Someone else who takes the old name is not followed.
        assertFalse(LibraryOps.isFollowed(follows, "oldname", "99"))
        // Without an id on one side, the login decides.
        assertTrue(LibraryOps.isFollowed(follows, "BOB", "7"))
    }

    @Test
    fun storedNamesFollowARenameAndAreLeftAloneOtherwise() {
        val follows = listOf(AppFollow("oldname", "OldName", "42"), AppFollow("bob", "Bob"))
        val renamed = LibraryOps.renamed(follows, listOf(User("42", "newname", "NewName")))
        assertEquals(listOf(AppFollow("newname", "NewName", "42"), AppFollow("bob", "Bob")), renamed)
        // Nothing changed: the same list, so nothing needs saving.
        assertSame(renamed, LibraryOps.renamed(renamed, listOf(User("42", "newname", "NewName"))))
    }

    @Test
    fun appFollowsToggleCaseInsensitivelyAndSurviveARoundTrip() {
        val one = LibraryOps.toggleFollow(emptyList(), AppFollow("alice", "Alice", "1"))
        assertTrue(LibraryOps.isFollowed(one, "ALICE"))
        assertEquals(one, LibraryOps.decodeFollows(LibraryOps.encodeFollows(one)))
        assertTrue(LibraryOps.toggleFollow(one, AppFollow("Alice", "Alice")).isEmpty())
    }

    @Test
    fun categoriesToggleByIdAndKeepTheirBoxArtTemplate() {
        val mc = FollowedCategory("27471", "Minecraft", "https://x/27471-{width}x{height}.jpg")
        val one = LibraryOps.toggleCategory(emptyList(), mc)
        assertEquals(one, LibraryOps.decodeCategories(LibraryOps.encodeCategories(one)))
        assertTrue(LibraryOps.toggleCategory(one, mc.copy(name = "renamed")).isEmpty()) // same id: unfollows
        assertTrue(LibraryOps.decodeCategories("not json").isEmpty())
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
