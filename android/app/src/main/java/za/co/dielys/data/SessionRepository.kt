package za.co.dielys.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import za.co.dielys.data.local.SessionStore
import za.co.dielys.data.remote.AccessTokens
import za.co.dielys.data.remote.ApiException
import za.co.dielys.data.remote.AuthApi
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the session and is the only thing that refreshes it.
 *
 * Refresh tokens rotate on every use, and presenting a spent one is treated as
 * theft — the server revokes every session for that user. So a refresh must never
 * run twice concurrently: two workers hitting a 401 at the same moment would
 * present the same token twice and lock the household out of their own list. The
 * mutex, and the re-check inside it, are what prevent that.
 */
@Singleton
class SessionRepository
    @Inject
    constructor(
        private val store: SessionStore,
        private val auth: AuthApi,
    ) : AccessTokens {
        private val refreshLock = Mutex()

        val deviceId: String get() = store.deviceId

        fun isSignedIn(): Boolean = store.refreshToken != null

        override suspend fun current(): String? = store.accessToken

        override suspend fun refreshed(): String? {
            val presented = store.refreshToken ?: return null
            return refreshLock.withLock {
                // Somebody else rotated it while this call waited for the lock.
                // Their access token is current; presenting `presented` again would
                // look like a replay.
                if (store.refreshToken != presented) return@withLock store.accessToken

                try {
                    val pair = auth.refresh(presented, store.deviceId)
                    store.accessToken = pair.accessToken
                    store.refreshToken = pair.refreshToken
                    store.userId = pair.userId
                    pair.accessToken
                } catch (_: ApiException.Rejected) {
                    // The refresh token is dead — expired, rotated, or revoked
                    // because a replay was detected. Only a fresh login recovers.
                    store.clearSession()
                    null
                }
            }
        }

        /**
         * `invalid-credentials` covers a wrong password, a wrong email, and an
         * account that does not exist. Do not try to tell the user which.
         */
        suspend fun signIn(
            email: String,
            password: String,
        ) {
            val pair = auth.login(email, password, store.deviceId)
            store.accessToken = pair.accessToken
            store.refreshToken = pair.refreshToken
            store.userId = pair.userId
        }

        /**
         * Clears the tokens only. The local replica and the outbox stay: an unsent
         * edit is still the user's, and signing back in should send it.
         */
        fun signOut() = store.clearSession()
    }
