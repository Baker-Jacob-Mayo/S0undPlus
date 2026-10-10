package com.s0undplus.twitch.eventsub

import com.s0undplus.twitch.Backoff
import com.s0undplus.twitch.TwitchApiException
import com.s0undplus.twitch.TwitchConfig
import com.s0undplus.twitch.TwitchJson
import com.s0undplus.twitch.auth.AuthSession
import com.s0undplus.twitch.await
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** The channel being watched started raiding [toLogin]. */
data class RaidEvent(
    val fromLogin: String,
    val toUserId: String,
    val toLogin: String,
    val toDisplayName: String,
    val viewers: Int,
)

internal sealed interface EventSubMessage {
    data class Welcome(val sessionId: String) : EventSubMessage
    data object Keepalive : EventSubMessage
    data class Reconnect(val url: String) : EventSubMessage
    data class Raid(val event: RaidEvent) : EventSubMessage
    data object Revocation : EventSubMessage
}

internal object EventSubParser {
    fun parse(text: String): EventSubMessage? {
        val root = try {
            TwitchJson.parseToJsonElement(text) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return null
        val type = root.obj("metadata")?.str("message_type") ?: return null
        val payload = root.obj("payload")
        return when (type) {
            "session_welcome" -> {
                // The field was called `websocket` in the RFC and `session` in the final API; accept both.
                val id = (payload?.obj("session") ?: payload?.obj("websocket"))?.str("id") ?: return null
                EventSubMessage.Welcome(id)
            }
            "session_keepalive" -> EventSubMessage.Keepalive
            "session_reconnect" -> payload?.obj("session")?.str("reconnect_url")?.let { EventSubMessage.Reconnect(it) }
            "revocation" -> EventSubMessage.Revocation
            "notification" -> {
                if (root.obj("metadata")?.str("subscription_type") != "channel.raid") return null
                val e = payload?.obj("event") ?: return null
                EventSubMessage.Raid(
                    RaidEvent(
                        fromLogin = e.str("from_broadcaster_user_login").orEmpty(),
                        toUserId = e.str("to_broadcaster_user_id") ?: return null,
                        toLogin = e.str("to_broadcaster_user_login") ?: return null,
                        toDisplayName = e.str("to_broadcaster_user_name") ?: e.str("to_broadcaster_user_login").orEmpty(),
                        viewers = (e["viewers"] as? JsonPrimitive)?.intOrNull ?: 0,
                    ),
                )
            }
            else -> null
        }
    }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}

/**
 * Notices when the channel you're watching raids another one, so the app can offer to follow along (the Twitch
 * website does the same). Uses the official EventSub WebSocket: it accepts the viewer's own user token and needs no
 * extra scope for `channel.raid`.
 */
class RaidWatcher(
    private val http: OkHttpClient,
    private val config: TwitchConfig,
    private val session: AuthSession,
    private val random: Random = Random.Default,
) {
    /** Emits each time [fromBroadcasterId] starts a raid. Reconnects on its own; completes only on cancellation. */
    fun raids(fromBroadcasterId: String): Flow<RaidEvent> = flow {
        var attempt = 0
        var url = config.eventSubUrl
        var resumed = false // after a server-requested reconnect the subscription carries over
        while (currentCoroutineContext().isActive) {
            try {
                socket(url).collect { message ->
                    when (message) {
                        is EventSubMessage.Welcome -> {
                            attempt = 0
                            if (!resumed) subscribe(message.sessionId, fromBroadcasterId)
                            resumed = false
                        }
                        is EventSubMessage.Raid -> emit(message.event)
                        is EventSubMessage.Reconnect -> {
                            url = message.url
                            resumed = true
                            throw ReconnectRequested()
                        }
                        EventSubMessage.Revocation -> throw IOException("EventSub subscription revoked")
                        EventSubMessage.Keepalive -> Unit
                    }
                }
                url = config.eventSubUrl
            } catch (_: ReconnectRequested) {
                continue
            } catch (e: CancellationException) {
                throw e
            } catch (e: TwitchApiException) {
                // 4xx = not allowed to subscribe (bad token, missing scope): retrying won't help. Give up quietly.
                if (e.status in 400..499) return@flow
                url = config.eventSubUrl
                resumed = false
            } catch (_: IOException) {
                url = config.eventSubUrl
                resumed = false
            }
            delay(Backoff.millis(attempt++, random))
        }
    }

    private class ReconnectRequested : RuntimeException()

    private fun socket(url: String): Flow<EventSubMessage> = callbackFlow {
        val ws = http.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    EventSubParser.parse(text)?.let { trySend(it) }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                    close(IOException("EventSub closed ($code)"))
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    close(IOException(t.message ?: "EventSub connection failed", t))
                }
            },
        )
        awaitClose { ws.close(1000, "done") }
    }

    private suspend fun subscribe(sessionId: String, fromBroadcasterId: String) {
        val tokens = session.freshTokens() ?: throw TwitchApiException(401, "Not signed in")
        val body = buildJsonObject {
            put("type", "channel.raid")
            put("version", "1")
            putJsonObject("condition") { put("from_broadcaster_user_id", fromBroadcasterId) }
            putJsonObject("transport") {
                put("method", "websocket")
                put("session_id", sessionId)
            }
        }
        val request = Request.Builder()
            .url("${config.helixUrl}/eventsub/subscriptions")
            .header("Authorization", "Bearer ${tokens.accessToken}")
            .header("Client-Id", config.clientId)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).await().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) throw TwitchApiException(response.code, "EventSub subscribe failed", text)
        }
    }
}
