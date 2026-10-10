package com.s0undplus.twitch.chat

import com.s0undplus.twitch.Backoff
import com.s0undplus.twitch.TwitchConfig
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Read-only chat over Twitch's IRC-over-WebSocket gateway using an anonymous `justinfan` login — no account or
 * token involved. Reconnects with exponential backoff; collect [events] for as long as chat should stay open.
 */
class ChatClient(
    private val http: OkHttpClient,
    private val config: TwitchConfig,
    private val random: Random = Random.Default,
) {
    fun events(channel: String): Flow<ChatEvent> = flow {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            try {
                session(channel).collect { event ->
                    if (event is ChatEvent.Connected) attempt = 0
                    emit(event)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                // fall through to the reconnect path
            }
            emit(ChatEvent.Disconnected(willRetry = true))
            delay(backoffMillis(attempt++))
        }
    }

    internal fun backoffMillis(attempt: Int): Long = Backoff.millis(attempt, random)

    private fun session(channel: String): Flow<ChatEvent> = callbackFlow {
        val nick = "justinfan${random.nextInt(10_000, 99_999)}"
        val socket = http.newWebSocket(
            Request.Builder().url(config.ircUrl).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    ChatProtocol.loginLines(nick, channel).forEach(webSocket::send)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    for (line in text.split("\r\n")) {
                        when (val action = ChatProtocol.handle(line)) {
                            is ProtocolAction.Reply -> webSocket.send(action.line)
                            is ProtocolAction.Emit -> trySend(action.event)
                            ProtocolAction.Reconnect -> {
                                webSocket.close(1001, "reconnect requested")
                                close(IOException("Twitch requested a reconnect"))
                            }
                            null -> Unit
                        }
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                    close(IOException("Chat connection closed ($code)"))
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    close(IOException(t.message ?: "Chat connection failed", t))
                }
            },
        )
        awaitClose { socket.close(1000, "done") }
    }
}
