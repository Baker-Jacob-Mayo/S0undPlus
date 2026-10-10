package com.s0undplus.twitch

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

/** Thrown for any non-2xx answer from a Twitch (or companion) endpoint. */
class TwitchApiException(
    val status: Int,
    message: String,
    val body: String? = null,
) : IOException(message)

internal val TwitchJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

/** Suspends until the call completes; cancelling the coroutine cancels the call. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resume(response)
            }
        },
    )
}

/** Executes the call and returns the body text, throwing [TwitchApiException] on non-2xx. */
internal suspend fun Call.awaitText(): String {
    await().use { response ->
        val text = response.body.string()
        if (!response.isSuccessful) {
            throw TwitchApiException(response.code, "HTTP ${response.code} from ${request().url.host}", text)
        }
        return text
    }
}
