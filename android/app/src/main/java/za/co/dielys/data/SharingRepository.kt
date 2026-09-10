package za.co.dielys.data

import za.co.dielys.data.remote.ApiException
import za.co.dielys.data.remote.ErrorCode
import za.co.dielys.data.remote.SyncApi
import za.co.dielys.data.sync.SyncScheduler
import javax.inject.Inject
import javax.inject.Singleton

/** What asking for an invite did, in terms a screen can act on. */
sealed interface InviteResult {
    /**
     * The server mailed the invite and scoped it to the address the caller
     * typed — no token here, since this device never needs to see the
     * bearer credential once the server has emailed it directly.
     */
    data object Sent : InviteResult

    /** L3: only the owner may invite. A member asking gets a 403. */
    data object NotYours : InviteResult

    data object Offline : InviteResult

    data class ServerProblem(
        val detail: String,
    ) : InviteResult
}

sealed interface JoinResult {
    data class Joined(
        val listId: String,
        /** Accepting the same invite twice is a no-op, not an error (L3). */
        val alreadyMember: Boolean,
    ) : JoinResult

    /**
     * Expired, already-rotated, mistyped, or an access token pasted where an
     * invite belongs — one answer, because the person pasting it can do the same
     * thing about all four: ask for a new one.
     */
    data object BadInvite : JoinResult

    /** L3: the invite is addressed to a different email than the one this
     * account is signed in as. Holding the link is no longer enough. */
    data object WrongRecipient : JoinResult

    data object Offline : JoinResult

    data class ServerProblem(
        val detail: String,
    ) : JoinResult
}

/**
 * Making and accepting invites (L3).
 *
 * Separate from [SessionRepository] because it is not about the session: these
 * are two authenticated calls that happen to be the only ones a screen makes
 * directly rather than through the outbox. They are safe to make directly
 * precisely because neither one is a list mutation — an invite creates no change
 * to order, and accepting one is idempotent, so nothing here needs F5.2's
 * idempotency key or the drain's retry.
 */
@Singleton
class SharingRepository
    @Inject
    constructor(
        private val api: SyncApi,
        private val scheduler: SyncScheduler,
    ) {
        suspend fun invite(
            listId: String,
            email: String,
            listTitle: String,
        ): InviteResult =
            try {
                api.createInvite(listId, email, listTitle)
                InviteResult.Sent
            } catch (error: ApiException.Rejected) {
                if (error.code == ErrorCode.FORBIDDEN) {
                    InviteResult.NotYours
                } else {
                    InviteResult.ServerProblem(error.code ?: "rejected")
                }
            } catch (_: ApiException.Unauthorized) {
                InviteResult.ServerProblem(ErrorCode.UNAUTHORIZED)
            } catch (_: ApiException.Transport) {
                InviteResult.Offline
            } catch (error: ApiException.Unavailable) {
                InviteResult.ServerProblem("server error ${error.status}")
            }

        /**
         * Accepting writes a membership row on the server and nothing locally: the
         * sync that follows is what pulls the list in and names it. Asking for one
         * here is why the list turns up straight away instead of at the next
         * half-hourly run (H3.12).
         */
        suspend fun join(inviteToken: String): JoinResult =
            try {
                val accepted = api.acceptInvite(inviteToken.trim())
                scheduler.requestSync()
                JoinResult.Joined(accepted.listId, accepted.alreadyMember)
            } catch (_: ApiException.Unauthorized) {
                // 401 here is the *invite* being rejected, not the session: the
                // session already survived the refresh-once path inside the client.
                JoinResult.BadInvite
            } catch (error: ApiException.Rejected) {
                if (error.code == ErrorCode.FORBIDDEN) {
                    JoinResult.WrongRecipient
                } else {
                    JoinResult.ServerProblem(error.code ?: "rejected")
                }
            } catch (_: ApiException.Transport) {
                JoinResult.Offline
            } catch (error: ApiException.Unavailable) {
                JoinResult.ServerProblem("server error ${error.status}")
            }
    }
