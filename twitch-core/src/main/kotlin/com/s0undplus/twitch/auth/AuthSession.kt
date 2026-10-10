package com.s0undplus.twitch.auth

import com.s0undplus.twitch.TwitchApiException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where the app keeps the login between launches (DataStore on Android, an in-memory fake in tests). */
interface TokenStore {
    suspend fun load(): AuthTokens?
    suspend fun save(tokens: AuthTokens?)
}

/**
 * Holds the signed-in state and hands out access tokens that are always fresh enough to use.
 * Refreshes are serialized so parallel Helix calls never race to burn the same refresh token.
 */
class AuthSession(
    private val auth: TwitchAuth,
    private val store: TokenStore,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val _tokens = MutableStateFlow<AuthTokens?>(null)

    /** Current login, or null when signed out. Observe this to drive navigation. */
    val tokens: StateFlow<AuthTokens?> = _tokens.asStateFlow()

    /** Loads the stored login at app start. */
    suspend fun restore() {
        mutex.withLock { _tokens.value = store.load() }
    }

    suspend fun signIn(tokens: AuthTokens) {
        mutex.withLock {
            store.save(tokens)
            _tokens.value = tokens
        }
    }

    suspend fun signOut() {
        mutex.withLock {
            store.save(null)
            _tokens.value = null
        }
    }

    /** Returns tokens valid for at least the next minute, refreshing if needed. Null when signed out. */
    suspend fun freshTokens(): AuthTokens? = mutex.withLock {
        val current = _tokens.value ?: return@withLock null
        if (current.expiresAtMillis - nowMillis() > REFRESH_MARGIN_MS) current else refreshLocked(current)
    }

    /** Called after a 401: refreshes unconditionally unless another caller already replaced [stale]. */
    suspend fun refreshAfterUnauthorized(stale: AuthTokens): AuthTokens? = mutex.withLock {
        val current = _tokens.value ?: return@withLock null
        if (current.accessToken != stale.accessToken) current else refreshLocked(current)
    }

    private suspend fun refreshLocked(current: AuthTokens): AuthTokens? {
        return try {
            val refreshed = auth.refresh(current)
            store.save(refreshed)
            _tokens.value = refreshed
            refreshed
        } catch (e: TwitchApiException) {
            // 400/401 mean the refresh token was revoked or expired: the viewer must sign in again.
            if (e.status == 400 || e.status == 401) {
                store.save(null)
                _tokens.value = null
                null
            } else {
                throw e
            }
        }
    }

    private companion object {
        const val REFRESH_MARGIN_MS = 60_000L
    }
}
