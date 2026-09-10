package za.co.dielys.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import za.co.dielys.data.local.SessionStore
import za.co.dielys.data.remote.AccessTokens
import za.co.dielys.data.remote.ApiException
import za.co.dielys.data.remote.AuthApi
import za.co.dielys.data.remote.ErrorCode
import za.co.dielys.data.remote.MIN_PASSWORD_LENGTH
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
 * What a registration attempt did (L2, ADR 0004).
 *
 * Unlike [SignInResult] this one *can* say the email is taken — the server says
 * so, and there is no honest way for it not to. It is a known
 * account-enumeration oracle, accepted and rate limited rather than papered over
 * with a lie the screen would then have to keep.
 */
sealed interface SignUpResult {
    data object Success : SignUpResult

    data object EmailTaken : SignUpResult

    /** Rejected before it was sent, or by the server. Same thing to the person. */
    data object PasswordTooShort : SignUpResult

    /** 429. Too many accounts from here, or too many everywhere today. */
    data object TooManyAttempts : SignUpResult

    data object Offline : SignUpResult

    data class ServerProblem(
        val detail: String,
    ) : SignUpResult
}

/**
 * What a magic-link request did (ADR 0005, docs/adr/0005-passwordless-email-magic-link.md).
 *
 * Deliberately no "no such account" case: the server answers the same way
 * whether or not the email has one, since [redeemMagicLink] creates it on
 * first use — there is nothing here for the screen to leak either.
 */
sealed interface MagicLinkRequestResult {
    data object Success : MagicLinkRequestResult

    data object TooManyAttempts : MagicLinkRequestResult

    data object Offline : MagicLinkRequestResult

    data class ServerProblem(
        val detail: String,
    ) : MagicLinkRequestResult
}

/** What redeeming a tapped magic link did (ADR 0005). */
sealed interface MagicLinkVerifyResult {
    data object Success : MagicLinkVerifyResult

    /** Wrong, already spent, or expired — one case, the same enumeration
     * reasoning [SignInResult.InvalidCredentials] uses. */
    data object InvalidOrExpired : MagicLinkVerifyResult

    data object Offline : MagicLinkVerifyResult

    data class ServerProblem(
        val detail: String,
    ) : MagicLinkVerifyResult
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

        /** Set from the sign-in form on success — the server never returns it. */
        val email: String? get() = store.email

        fun isSignedIn(): Boolean = store.refreshToken != null

        /**
         * The protocol's floor, re-exported so a screen can say it without
         * importing `data.remote` (E1.2).
         */
        val minPasswordLength: Int get() = MIN_PASSWORD_LENGTH

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
                store.email = email
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
         * Registers and signs in, in one call — the server answers a registration
         * with the same token pair a login gets, so there is no second round trip
         * to fail at.
         *
         * The length check happens here as well as on the server. Not because the
         * server's cannot be trusted, but because a person who has just typed a
         * short password should be told before it goes anywhere.
         */
        suspend fun signUp(
            email: String,
            password: String,
        ): SignUpResult {
            if (password.length < MIN_PASSWORD_LENGTH) return SignUpResult.PasswordTooShort

            return try {
                val pair = auth.register(email, password, store.deviceId)
                store.accessToken = pair.accessToken
                store.refreshToken = pair.refreshToken
                store.userId = pair.userId
                store.email = email
                scheduler.requestSync()
                SignUpResult.Success
            } catch (error: ApiException.Rejected) {
                when (error.code) {
                    ErrorCode.ALREADY_EXISTS -> SignUpResult.EmailTaken
                    ErrorCode.RATE_LIMITED -> SignUpResult.TooManyAttempts
                    ErrorCode.MALFORMED -> SignUpResult.PasswordTooShort
                    else -> SignUpResult.ServerProblem(error.code ?: "rejected")
                }
            } catch (_: ApiException.Unauthorized) {
                SignUpResult.ServerProblem(ErrorCode.UNAUTHORIZED)
            } catch (_: ApiException.Transport) {
                SignUpResult.Offline
            } catch (error: ApiException.Unavailable) {
                SignUpResult.ServerProblem("server error ${error.status}")
            }
        }

        /**
         * Clears the tokens only. The local replica and the outbox stay: an unsent
         * edit is still the user's, and signing back in should send it.
         */
        fun signOut() = store.clearSession()

        /** Mints and mails a sign-in link (ADR 0005). Nothing local changes yet —
         * there is no session until the mailed link is tapped and redeemed. */
        suspend fun requestMagicLink(email: String): MagicLinkRequestResult =
            try {
                auth.requestMagicLink(email)
                MagicLinkRequestResult.Success
            } catch (error: ApiException.Rejected) {
                if (error.code == ErrorCode.RATE_LIMITED) {
                    MagicLinkRequestResult.TooManyAttempts
                } else {
                    MagicLinkRequestResult.ServerProblem(error.code ?: "rejected")
                }
            } catch (_: ApiException.Transport) {
                MagicLinkRequestResult.Offline
            } catch (error: ApiException.Unavailable) {
                MagicLinkRequestResult.ServerProblem("server error ${error.status}")
            }

        /**
         * Redeems a tapped magic link (ADR 0005) — creates the account on first
         * use and signs in either way, same shape as [signUp]/[signIn] collapsing
         * into one call.
         *
         * Unlike [signIn], the server never hands back the email the link was
         * for, so [SessionRepository.email] stays whatever it already was —
         * usually null on this path, since nobody typed anything.
         */
        suspend fun redeemMagicLink(token: String): MagicLinkVerifyResult =
            try {
                val pair = auth.verifyMagicLink(token, store.deviceId)
                store.accessToken = pair.accessToken
                store.refreshToken = pair.refreshToken
                store.userId = pair.userId
                scheduler.requestSync()
                MagicLinkVerifyResult.Success
            } catch (error: ApiException.Rejected) {
                if (error.code == ErrorCode.INVALID_TOKEN ||
                    error.code == ErrorCode.TOKEN_EXPIRED
                ) {
                    MagicLinkVerifyResult.InvalidOrExpired
                } else {
                    MagicLinkVerifyResult.ServerProblem(error.code ?: "rejected")
                }
            } catch (_: ApiException.Unauthorized) {
                MagicLinkVerifyResult.InvalidOrExpired
            } catch (_: ApiException.Transport) {
                MagicLinkVerifyResult.Offline
            } catch (error: ApiException.Unavailable) {
                MagicLinkVerifyResult.ServerProblem("server error ${error.status}")
            }
    }
