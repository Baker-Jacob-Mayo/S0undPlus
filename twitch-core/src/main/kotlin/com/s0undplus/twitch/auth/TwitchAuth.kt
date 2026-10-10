package com.s0undplus.twitch.auth

import com.s0undplus.twitch.TwitchApiException
import com.s0undplus.twitch.TwitchConfig
import com.s0undplus.twitch.TwitchJson
import com.s0undplus.twitch.await
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/** Response of `POST /oauth2/device`. Show [userCode] and send the viewer to [verificationUri]. */
@Serializable
data class DeviceCode(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_uri") val verificationUri: String,
    @SerialName("expires_in") val expiresInSeconds: Int = 1800,
    @SerialName("interval") val intervalSeconds: Int = 5,
)

@Serializable
internal data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresInSeconds: Long = 0,
    val scope: List<String> = emptyList(),
)

@Serializable
internal data class ValidateResponse(
    @SerialName("client_id") val clientId: String = "",
    val login: String = "",
    @SerialName("user_id") val userId: String = "",
    val scopes: List<String> = emptyList(),
    @SerialName("expires_in") val expiresInSeconds: Long = 0,
)

@Serializable
internal data class OAuthError(val status: Int = 0, val message: String = "", val error: String = "")

/** Persisted login. Epoch-millis expiry so it survives process death without clock games. */
@Serializable
data class AuthTokens(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtMillis: Long,
    val userId: String,
    val login: String,
    val scopes: List<String> = emptyList(),
)

/** Reasons a device login can fail terminally. Everything else (network blips) is an [IOException]. */
sealed class DeviceLoginException(message: String) : IOException(message) {
    class Expired : DeviceLoginException("The login code expired before it was entered")
    class Denied : DeviceLoginException("Login was denied on the Twitch website")
}

/**
 * Twitch OAuth Device Code Grant: the right flow for a TV (no keyboard, no browser). The viewer enters a short code
 * at twitch.tv/activate on their phone while the TV polls for the result. Public client: no client secret.
 */
class TwitchAuth(
    private val http: OkHttpClient,
    private val config: TwitchConfig,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun requestDeviceCode(scopes: List<String>): DeviceCode {
        val body = FormBody.Builder()
            .add("client_id", config.clientId)
            .add("scopes", scopes.joinToString(" "))
            .build()
        val request = Request.Builder().url("${config.oauthUrl}/device").post(body).build()
        http.newCall(request).await().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) throw TwitchApiException(response.code, "Device code request failed", text)
            return TwitchJson.decodeFromString(DeviceCode.serializer(), text)
        }
    }

    /**
     * Polls until the viewer approves (returns tokens), denies, or the code expires. Honors the server's polling
     * interval and the `slow_down` signal. Cancel the calling coroutine to abort.
     */
    suspend fun awaitDeviceLogin(code: DeviceCode, scopes: List<String>): AuthTokens {
        var intervalMs = code.intervalSeconds.coerceAtLeast(1) * 1000L
        val deadline = nowMillis() + code.expiresInSeconds * 1000L
        while (true) {
            delay(intervalMs)
            if (nowMillis() > deadline) throw DeviceLoginException.Expired()
            val body = FormBody.Builder()
                .add("client_id", config.clientId)
                .add("scopes", scopes.joinToString(" "))
                .add("device_code", code.deviceCode)
                .add("grant_type", DEVICE_GRANT)
                .build()
            val request = Request.Builder().url("${config.oauthUrl}/token").post(body).build()
            http.newCall(request).await().use { response ->
                val text = response.body.string()
                if (response.isSuccessful) {
                    return finish(TwitchJson.decodeFromString(TokenResponse.serializer(), text), scopes)
                }
                when (val reason = errorReason(text)) {
                    "authorization_pending" -> Unit
                    "slow_down" -> intervalMs += 5_000
                    "access_denied" -> throw DeviceLoginException.Denied()
                    "expired_token", "invalid device code" -> throw DeviceLoginException.Expired()
                    else -> throw TwitchApiException(response.code, "Device login failed: $reason", text)
                }
            }
        }
    }

    /** Exchanges the refresh token for a fresh access token. Throws [TwitchApiException] (400/401) if revoked. */
    suspend fun refresh(tokens: AuthTokens): AuthTokens {
        val refreshToken = tokens.refreshToken ?: throw TwitchApiException(401, "No refresh token stored")
        val body = FormBody.Builder()
            .add("client_id", config.clientId)
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .build()
        val request = Request.Builder().url("${config.oauthUrl}/token").post(body).build()
        http.newCall(request).await().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) throw TwitchApiException(response.code, "Token refresh failed", text)
            val parsed = TwitchJson.decodeFromString(TokenResponse.serializer(), text)
            return tokens.copy(
                accessToken = parsed.accessToken,
                refreshToken = parsed.refreshToken ?: tokens.refreshToken,
                expiresAtMillis = nowMillis() + parsed.expiresInSeconds * 1000L,
                scopes = parsed.scope.ifEmpty { tokens.scopes },
            )
        }
    }

    private suspend fun finish(token: TokenResponse, requestedScopes: List<String>): AuthTokens {
        val request = Request.Builder()
            .url("${config.oauthUrl}/validate")
            .header("Authorization", "OAuth ${token.accessToken}")
            .build()
        http.newCall(request).await().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) throw TwitchApiException(response.code, "Token validation failed", text)
            val who = TwitchJson.decodeFromString(ValidateResponse.serializer(), text)
            return AuthTokens(
                accessToken = token.accessToken,
                refreshToken = token.refreshToken,
                expiresAtMillis = nowMillis() + token.expiresInSeconds * 1000L,
                userId = who.userId,
                login = who.login,
                scopes = token.scope.ifEmpty { requestedScopes },
            )
        }
    }

    private fun errorReason(body: String): String = try {
        val e = TwitchJson.decodeFromString(OAuthError.serializer(), body)
        e.message.ifEmpty { e.error }
    } catch (_: Exception) {
        ""
    }

    private companion object {
        const val DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
    }
}
