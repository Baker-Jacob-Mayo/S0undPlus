package com.s0undplus.twitch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TwitchLanguagesTest {
    @Test
    fun deviceLanguageIsUsedWhenTwitchHasIt() {
        assertEquals("de", TwitchLanguages.defaultFor("de"))
        assertEquals("ja", TwitchLanguages.defaultFor("JA"))
    }

    @Test
    fun unknownDeviceLanguagesFallBackToEnglish() {
        assertEquals("en", TwitchLanguages.defaultFor("xx"))
        assertEquals("en", TwitchLanguages.defaultFor(""))
    }

    @Test
    fun namesAreLookedUpAndUnknownCodesComeBackAsTheyAre() {
        assertEquals("Deutsch", TwitchLanguages.name("de"))
        assertEquals("zz", TwitchLanguages.name("zz"))
    }

    @Test
    fun nextWalksTheWholeListAndWrapsAround() {
        var code = TwitchLanguages.ALL.first().first
        val seen = mutableListOf(code)
        repeat(TwitchLanguages.ALL.size) {
            code = TwitchLanguages.next(code)
            seen += code
        }
        assertEquals(TwitchLanguages.ALL.first().first, seen.last())
        assertEquals(TwitchLanguages.ALL.map { it.first }, seen.dropLast(1))
        assertEquals("en", TwitchLanguages.next("not-a-code"))
    }

    @Test
    fun codesAreUnique() {
        assertEquals(TwitchLanguages.ALL.size, TwitchLanguages.ALL.map { it.first }.toSet().size)
        assertTrue(TwitchLanguages.ALL.all { it.first.length == 2 })
    }
}
