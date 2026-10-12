package com.s0undplus.tv.ui.nav

import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import com.s0undplus.twitch.Stream
import com.s0undplus.twitch.Video

/** What the player should play. */
sealed interface PlayTarget {
    data class Live(
        val login: String,
        val displayName: String,
        val userId: String?,
        val title: String? = null,
        val game: String? = null,
    ) : PlayTarget

    data class Vod(
        val id: String,
        val title: String,
        val channelName: String,
        val channelLogin: String,
        val channelUserId: String?,
        val durationSeconds: Long,
        val thumbnailUrl: String = "",
    ) : PlayTarget
}

sealed interface Route {
    data object Home : Route
    data class Category(val id: String, val name: String, val boxArtUrl: String = "") : Route
    data class Channel(val login: String) : Route
    data class Play(val target: PlayTarget) : Route
}

fun Video.toPlayTarget(): PlayTarget.Vod =
    PlayTarget.Vod(id, title, userName, userLogin, userId, durationSeconds, thumbnail())

fun Stream.toPlayTarget(): PlayTarget.Live =
    PlayTarget.Live(userLogin, userName, userId, title.ifBlank { null }, gameName.ifBlank { null })

/**
 * A tiny back stack. Compose Navigation is in maintenance mode and this app has four destinations, so a list of
 * routes is clearer than a graph. Lives in a ViewModel so it survives configuration changes.
 *
 * Each entry has its own [ViewModelStore] (see [storeFor]): a screen's ViewModels live exactly as long as its place
 * on the stack, so coming back to a page finds it as it was, while a later, separate visit to the same page starts
 * fresh, and popped pages release their data.
 */
class NavViewModel : ViewModel() {
    private class Entry(val id: Long, val route: Route)

    private var nextId = 0L
    private val entries = mutableStateListOf(Entry(nextId++, Route.Home))
    private val stores = HashMap<Long, ViewModelStore>()

    val current: Route get() = entries.last().route

    /** Identifies the top entry: two visits to the same route are different entries. */
    val currentEntryId: Long get() = entries.last().id

    val canPop: Boolean get() = entries.size > 1

    fun push(route: Route) {
        entries.add(Entry(nextId++, route))
    }

    fun pop() {
        if (canPop) release(entries.removeAt(entries.lastIndex))
    }

    /** Swaps the top screen (switching stream from the player shouldn't leave the old stream on the back stack). */
    fun replaceTop(route: Route) {
        if (entries.isNotEmpty()) release(entries.removeAt(entries.lastIndex))
        entries.add(Entry(nextId++, route))
    }

    /** The ViewModels of one stack entry. */
    fun storeFor(entryId: Long): ViewModelStore = stores.getOrPut(entryId) { ViewModelStore() }

    private fun release(entry: Entry) {
        stores.remove(entry.id)?.clear()
    }

    override fun onCleared() {
        stores.values.forEach { it.clear() }
        stores.clear()
    }
}
