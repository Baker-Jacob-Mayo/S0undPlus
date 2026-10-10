package com.s0undplus.tv.ui.player

import com.s0undplus.tv.AppContainer
import com.s0undplus.tv.data.AppSettings
import com.s0undplus.twitch.chat.BadgeDirectory
import com.s0undplus.twitch.chat.ChatEvent
import com.s0undplus.twitch.chat.ChatFilter
import com.s0undplus.twitch.chat.ChatMessage
import com.s0undplus.twitch.chat.EmoteDirectory
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One row in the chat list: either a user message or a system notice (sub, raid, moderation...). */
data class ChatItem(val key: Long, val message: ChatMessage? = null, val notice: String? = null)

/**
 * Live chat for one channel. Incoming messages are buffered and published at most every [FLUSH_MS] so a chat doing
 * dozens of messages a second costs one recomposition per tick instead of one per message — important on TV SoCs.
 */
class ChatController(
    private val c: AppContainer,
    private val login: String,
    private val knownUserId: String?,
    private val settings: StateFlow<AppSettings>,
    private val scope: CoroutineScope,
) {
    private val _items = MutableStateFlow<List<ChatItem>>(emptyList())
    val items: StateFlow<List<ChatItem>> = _items.asStateFlow()

    private val _emotes = MutableStateFlow(EmoteDirectory.EMPTY)
    val emotes: StateFlow<EmoteDirectory> = _emotes.asStateFlow()

    private val _badges = MutableStateFlow(BadgeDirectory.EMPTY)
    val badges: StateFlow<BadgeDirectory> = _badges.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val pending = ArrayList<ChatItem>()
    private val seenIds = LinkedHashSet<String>()
    private var nextKey = 0L
    private var filter: ChatFilter = settings.value.chatFilter

    fun start() {
        scope.launch { settings.collect { filter = it.chatFilter } }
        scope.launch { loadDecorations() }
        scope.launch {
            while (isActive) {
                delay(FLUSH_MS)
                flush()
            }
        }
        scope.launch {
            if (settings.value.chatHistory) {
                for (message in c.recentMessages.fetch(login, HISTORY_LIMIT)) accept(message)
            }
            c.chat.events(login).collect(::onEvent)
        }
    }

    /** Emotes and badges are cosmetic: any failure just leaves chat with plain text and no badge icons. */
    private suspend fun loadDecorations() {
        val userId = knownUserId ?: try {
            c.helix.userByLogin(login)?.id
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        scope.launch {
            try {
                _emotes.value = c.emotes.load(userId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // keep EMPTY
            }
        }
        scope.launch {
            try {
                _badges.value = c.helix.badges(userId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // keep EMPTY
            }
        }
    }

    private fun onEvent(event: ChatEvent) {
        when (event) {
            is ChatEvent.Connected -> _connected.value = true
            is ChatEvent.Disconnected -> {
                _connected.value = false
                add(ChatItem(nextKey++, notice = "Chat disconnected — reconnecting…"))
            }
            is ChatEvent.Message -> accept(event.message)
            is ChatEvent.Notice -> add(ChatItem(nextKey++, notice = event.text))
            is ChatEvent.ClearChat -> {
                flush()
                val target = event.targetLogin
                _items.value = if (target == null) {
                    listOf(ChatItem(nextKey++, notice = "Chat was cleared by a moderator"))
                } else {
                    _items.value.filterNot { it.message?.login.equals(target, ignoreCase = true) }
                }
            }
            is ChatEvent.ClearMessage -> {
                flush()
                _items.value = _items.value.filterNot { it.message?.id == event.messageId }
            }
        }
    }

    private fun accept(message: ChatMessage) {
        if (message.id.isNotEmpty() && !seenIds.add(message.id)) return // already shown via history
        if (seenIds.size > SEEN_LIMIT) seenIds.remove(seenIds.first())
        if (!filter.allows(message)) return
        add(ChatItem(nextKey++, message = message))
    }

    private fun add(item: ChatItem) {
        pending += item
    }

    private fun flush() {
        if (pending.isEmpty()) return
        val merged = _items.value + pending
        pending.clear()
        _items.value = if (merged.size > MAX_ITEMS) merged.subList(merged.size - MAX_ITEMS, merged.size) else merged
    }

    private companion object {
        const val FLUSH_MS = 250L
        const val MAX_ITEMS = 120
        const val SEEN_LIMIT = 600
        const val HISTORY_LIMIT = 60
    }
}
