package com.s0undplus.tv.ui.player

import com.s0undplus.tv.AppContainer
import com.s0undplus.tv.data.AppSettings
import com.s0undplus.tv.data.DeletedStyle
import com.s0undplus.twitch.chat.BadgeDirectory
import com.s0undplus.twitch.chat.ChatEvent
import com.s0undplus.twitch.chat.ChatFilter
import com.s0undplus.twitch.chat.ChatMessage
import com.s0undplus.twitch.chat.EmoteDirectory
import com.s0undplus.twitch.chat.VodChatReplay
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * One row in the chat list: either a user message or a system notice (sub, raid, moderation...). [deleted] marks a
 * message a moderator removed, which the viewer may have chosen to show greyed out rather than hide.
 */
data class ChatItem(
    val key: Long,
    val message: ChatMessage? = null,
    val notice: String? = null,
    val deleted: Boolean = false,
)

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

    /** Set while VOD chat replay is failing (the video itself keeps playing); null when fine. */
    private val _replayError = MutableStateFlow<String?>(null)
    val replayError: StateFlow<String?> = _replayError.asStateFlow()

    /** False for VOD replay: there is no connection to wait for. */
    var isLiveChat: Boolean = true
        private set

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

    /**
     * Replays the chat of a past broadcast in sync with the video. [position] is polled once a second; a backwards or
     * large forward jump is treated as a seek and restarts the replay from the new position.
     */
    fun startReplay(videoId: String, position: () -> Long) {
        isLiveChat = false
        scope.launch { settings.collect { filter = it.chatFilter } }
        scope.launch { loadDecorations() }
        scope.launch {
            while (isActive) {
                delay(FLUSH_MS)
                flush()
            }
        }
        scope.launch {
            val replay = VodChatReplay { offset, cursor -> c.vodChat.page(videoId, offset, cursor) }
            var last = position()
            while (isActive) {
                val now = position()
                if (now < last || now - last > SEEK_JUMP_MS) {
                    replay.seek()
                    pending.clear()
                    _items.value = emptyList()
                    seenIds.clear()
                }
                try {
                    for (message in replay.advance(now)) accept(message)
                    _replayError.value = null
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    _replayError.value = "Chat replay isn't available for this video."
                    delay(REPLAY_RETRY_MS)
                }
                last = now
                delay(REPLAY_POLL_MS)
            }
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
            is ChatEvent.Notice -> if (settings.value.showNotices) add(ChatItem(nextKey++, notice = event.text))
            is ChatEvent.ClearChat -> {
                flush()
                val target = event.targetLogin
                if (target == null) {
                    _items.value = listOf(ChatItem(nextKey++, notice = "Chat was cleared by a moderator"))
                } else {
                    _items.value = applyDeletion(_items.value) { it.message?.login.equals(target, ignoreCase = true) }
                }
            }
            is ChatEvent.ClearMessage -> {
                flush()
                _items.value = applyDeletion(_items.value) { it.message?.id == event.messageId }
            }
        }
    }

    /** Hides, greys out or leaves alone the matching messages, according to the viewer's setting. */
    private fun applyDeletion(items: List<ChatItem>, matches: (ChatItem) -> Boolean): List<ChatItem> =
        when (settings.value.deletedStyle) {
            DeletedStyle.HIDE -> items.filterNot(matches)
            DeletedStyle.GREY -> items.map { if (matches(it)) it.copy(deleted = true) else it }
            DeletedStyle.SHOW -> items
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
        const val REPLAY_POLL_MS = 1_000L
        const val REPLAY_RETRY_MS = 10_000L
        const val SEEK_JUMP_MS = 4_000L
    }
}
