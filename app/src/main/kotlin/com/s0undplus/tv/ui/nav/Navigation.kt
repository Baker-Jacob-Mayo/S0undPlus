package com.s0undplus.tv.ui.nav

import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import com.s0undplus.twitch.Stream

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
        val durationSeconds: Long,
    ) : PlayTarget
}

sealed interface Route {
    data object Home : Route
    data class Category(val id: String, val name: String) : Route
    data class Channel(val login: String) : Route
    data class Play(val target: PlayTarget) : Route
}

fun Stream.toPlayTarget(): PlayTarget.Live =
    PlayTarget.Live(userLogin, userName, userId, title.ifBlank { null }, gameName.ifBlank { null })

/**
 * A tiny back stack. Compose Navigation is in maintenance mode and this app has four destinations, so a list of
 * routes is clearer than a graph. Lives in a ViewModel so it survives configuration changes.
 */
class NavViewModel : ViewModel() {
    val stack = mutableStateListOf<Route>(Route.Home)

    val current: Route get() = stack.last()
    val canPop: Boolean get() = stack.size > 1

    fun push(route: Route) {
        stack.add(route)
    }

    fun pop() {
        if (canPop) stack.removeAt(stack.lastIndex)
    }
}
