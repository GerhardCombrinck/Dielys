package za.co.dielys.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import za.co.dielys.data.local.SessionStore
import za.co.dielys.data.remote.AccessTokens
import za.co.dielys.data.remote.ApiException
import za.co.dielys.data.remote.AuthApi
import za.co.dielys.data.remote.ErrorCode
import za.co.dielys.data.sync.SyncScheduler
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What a sign-in attempt did, in terms the UI can act on.
 *
 * Deliberately not an [ApiException]: the screens must not import
 * `data.remote` (E1.2), and the distinctions that matter to a person are not the
 * distinctions that matter to a transport.
 */
sealed interface SignInResult {
    data object Success : SignInResult

    /**
     * The server answers `invalid-credentials` for a wrong password, a wrong
     * email, and an account that does not exist, on purpose — three answers would
     * let anyone test which addresses have accounts. The screen must not try to
     * be more helpful than that.
     */
    data object InvalidCredentials : SignInResult

    /** No network. Nothing was sent, so trying again later costs nothing. */
    data object Offline : SignInResult

    /** The server answered, badly. [detail] is for the user, not for a log. */
    data class ServerProblem(
        val detail: String,
    ) : SignInResult
}

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
        private val scheduler: SyncScheduler,
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
         *
         * A fresh sign-in is the one moment this device knows nothing, so it asks
         * for a sync straight after — otherwise a new phone would sit on an empty
         * screen until the half-hourly worker got round to it.
         */
        suspend fun signIn(
            email: String,
            password: String,
        ): SignInResult =
            try {
                val pair = auth.login(email, password, store.deviceId)
                store.accessToken = pair.accessToken
                store.refreshToken = pair.refreshToken
                store.userId = pair.userId
                scheduler.requestSync()
                SignInResult.Success
            } catch (error: ApiException.Rejected) {
                if (error.code == ErrorCode.INVALID_CREDENTIALS) {
                    SignInResult.InvalidCredentials
                } else {
                    SignInResult.ServerProblem(error.code ?: "rejected")
                }
            } catch (_: ApiException.Unauthorized) {
                SignInResult.InvalidCredentials
            } catch (_: ApiException.Transport) {
                SignInResult.Offline
            } catch (error: ApiException.Unavailable) {
                SignInResult.ServerProblem("server error ${error.status}")
            }

        /**
         * Clears the tokens only. The local replica and the outbox stay: an unsent
         * edit is still the user's, and signing back in should send it.
         */
        fun signOut() = store.clearSession()
    }
