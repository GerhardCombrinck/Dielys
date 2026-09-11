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
 * One person on a shared list, as the screen needs them (#60).
 *
 * Not the wire type: the UI may not import `data.remote` at all (E1.2), and
 * that rule is worth more than saving this mapping. The email is what names
 * them — there is no display name in this system, only the address the owner
 * typed to invite them.
 */
data class Member(
    val userId: String,
    val email: String,
    val isOwner: Boolean,
)

/** Who is on a list, for the sheet behind the shared icon (#60). */
sealed interface MembersResult {
    data class Loaded(
        val members: List<Member>,
    ) : MembersResult

    /** Not on this list any more — someone removed this account while the
     *  screen was open, or between opening the sheet and asking. */
    data object NotYours : MembersResult

    data object Offline : MembersResult

    data class ServerProblem(
        val detail: String,
    ) : MembersResult
}

sealed interface RemoveResult {
    data object Removed : RemoveResult

    /**
     * The server refused: a member trying to remove somebody other than
     * themselves, or an owner trying to leave their own list. One answer for
     * both, because the server gives one — and because the screen only ever
     * offers the actions that are allowed, so reaching this at all means the
     * membership changed underneath.
     */
    data object NotAllowed : RemoveResult

    data object Offline : RemoveResult

    data class ServerProblem(
        val detail: String,
    ) : RemoveResult
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

        /**
         * Who is on [listId] (#60). Read live rather than kept in Room: it is
         * only ever looked at while a sheet is open, and a stale answer about
         * who can see your shopping list is worse than a spinner.
         */
        suspend fun members(listId: String): MembersResult =
            try {
                MembersResult.Loaded(
                    api.listMembers(listId).map {
                        Member(userId = it.userId, email = it.email, isOwner = it.isOwner)
                    },
                )
            } catch (error: ApiException.Rejected) {
                if (error.code == ErrorCode.FORBIDDEN) {
                    MembersResult.NotYours
                } else {
                    MembersResult.ServerProblem(error.code ?: "rejected")
                }
            } catch (_: ApiException.Unauthorized) {
                MembersResult.ServerProblem(ErrorCode.UNAUTHORIZED)
            } catch (_: ApiException.Transport) {
                MembersResult.Offline
            } catch (error: ApiException.Unavailable) {
                MembersResult.ServerProblem("server error ${error.status}")
            }

        /**
         * Takes one person off [listId] — the owner removing somebody, or this
         * account leaving (#60).
         *
         * Asks for a sync afterwards for the same reason [join] does: the member
         * count on every affected screen comes from `/auth/memberships`, and
         * waiting up to half an hour to stop saying "shared" would be its own
         * small lie (H3.12).
         */
        suspend fun removeMember(
            listId: String,
            userId: String,
        ): RemoveResult =
            try {
                api.removeMember(listId, userId)
                scheduler.requestSync()
                RemoveResult.Removed
            } catch (error: ApiException.Rejected) {
                if (error.code == ErrorCode.FORBIDDEN) {
                    RemoveResult.NotAllowed
                } else {
                    RemoveResult.ServerProblem(error.code ?: "rejected")
                }
            } catch (_: ApiException.Unauthorized) {
                RemoveResult.ServerProblem(ErrorCode.UNAUTHORIZED)
            } catch (_: ApiException.Transport) {
                RemoveResult.Offline
            } catch (error: ApiException.Unavailable) {
                RemoveResult.ServerProblem("server error ${error.status}")
            }
    }
