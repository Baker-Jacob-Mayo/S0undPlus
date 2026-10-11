package com.s0undplus.twitch

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

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

/** The most [await] will hold in memory for one answer; everything this app asks for through it is JSON or a playlist. */
internal const val MAX_AWAITED_BODY_BYTES = 16L * 1024 * 1024

/**
 * Suspends until the call completes *and its whole body has arrived*; cancelling the coroutine cancels the call.
 *
 * The body is read here, on one of OkHttp's own threads, before the caller resumes. A caller on the main thread
 * (every ViewModel scope) would otherwise be resumed as soon as the headers were in and then block on the socket
 * inside `body.string()`, which Android refuses with `NetworkOnMainThreadException` whenever the body had not
 * already arrived with the headers. The returned response holds the body in memory, so reading it later is always
 * safe and instant. An answer larger than [maxBodyBytes] fails with an [IOException].
 */
internal suspend fun Call.await(maxBodyBytes: Long = MAX_AWAITED_BODY_BYTES): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val buffered = try {
                    response.withBodyInMemory(maxBodyBytes)
                } catch (e: Exception) {
                    // Not only IOException: anything thrown here would otherwise leave the caller suspended for ever.
                    response.close()
                    if (!cont.isCancelled) cont.resumeWithException(e)
                    return
                }
                // If the caller was cancelled while the response was arriving nobody will read it: close it, or
                // OkHttp holds the connection until GC and logs a leak.
                cont.resume(buffered) { _, _, _ -> buffered.close() }
            }
        },
    )
}

private fun Response.withBodyInMemory(maxBytes: Long): Response {
    val original = body
    val source = original.source()
    if (source.request(maxBytes + 1)) throw IOException("The answer from ${request.url.host} is larger than $maxBytes bytes")
    val bytes = source.readByteArray()
    close()
    return newBuilder().body(bytes.toResponseBody(original.contentType())).build()
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
