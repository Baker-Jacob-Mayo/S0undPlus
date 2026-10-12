package com.s0undplus.tv.data

import android.media.MediaCodecList
import android.os.Build
import com.s0undplus.twitch.TwitchConfig
import com.s0undplus.twitch.auth.AuthSession
import com.s0undplus.twitch.auth.AuthTokens
import com.s0undplus.twitch.auth.DeviceCode
import com.s0undplus.twitch.auth.TokenStore
import com.s0undplus.twitch.auth.TwitchAuth
import com.s0undplus.twitch.playback.ViewerToken
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient

/**
 * The optional second sign-in, to Twitch's own TV app, used for nothing but playback tickets. Twitch sends 1440p only
 * to signed-in viewers and only through its own apps' tickets (S0undPlus's own sign-in can't ask for one); a ticket
 * asked for this way also carries a Turbo or subscriber's ad-free viewing. Unofficial, like the rest of playback.
 */
class ViewerLogin(http: OkHttpClient, config: TwitchConfig, store: TokenStore) {
    /** Twitch's TV app: it signs in with a code at twitch.tv/activate, just as S0undPlus does. */
    private val clientId = config.androidClientId
    private val auth = TwitchAuth(http, config.copy(clientId = clientId))
    private val session = AuthSession(auth, store)
    private val restoreLock = Mutex()
    private var restored = false

    /** The second sign-in, or null when there isn't one. */
    val tokens: StateFlow<AuthTokens?> = session.tokens

    suspend fun restore() = restoreLock.withLock {
        if (!restored) {
            session.restore()
            restored = true
        }
    }

    suspend fun requestCode(): DeviceCode = auth.requestDeviceCode(SCOPES)

    /** Waits until the code is entered on the phone (or refused, or expires), then keeps the sign-in. */
    suspend fun awaitSignIn(code: DeviceCode) {
        session.signIn(auth.awaitDeviceLogin(code, SCOPES))
    }

    suspend fun signOut() = session.signOut()

    /** A signed-in identity for a playback ticket, or null (signed out, or the sign-in can't be renewed). */
    suspend fun ticket(): ViewerToken? {
        restore()
        return try {
            session.freshTokens()?.let { ViewerToken(it.accessToken, clientId) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null // the anonymous ticket still plays
        }
    }

    private companion object {
        /** Nothing beyond signing in: a playback ticket needs no other permission. */
        val SCOPES = emptyList<String>()
    }
}

/** What the TV's video decoders can play, checked once. */
object VideoDecoders {
    /**
     * Whether a hardware decoder plays HEVC at 1440p and 60 fps. Twitch sends 1440p only as HEVC, and a box that would
     * decode it in software would stutter, so it is asked for only when this holds.
     */
    val hevc1440: Boolean by lazy {
        try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                !info.isEncoder &&
                    (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || info.isHardwareAccelerated) &&
                    info.supportedTypes.any { it.equals(HEVC, ignoreCase = true) } &&
                    info.getCapabilitiesForType(HEVC).videoCapabilities?.areSizeAndRateSupported(2560, 1440, 60.0) == true
            }
        } catch (_: RuntimeException) {
            false
        }
    }

    /** The codecs to offer Twitch: HEVC first when signed in for 1440p and the TV can play it. */
    fun forPlayback(signedIn: Boolean): List<String> = if (signedIn && hevc1440) listOf("h265", "h264") else listOf("h264")

    private const val HEVC = "video/hevc"
}
