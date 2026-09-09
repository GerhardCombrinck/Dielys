package za.co.dielys.data.remote

/**
 * The server, as the sync engine sees it. An interface so the engine can be tested
 * on the JVM against a fake without an emulator or a socket (H1) — the offline
 * scenarios in H3 are mostly "what does the client do when this throws".
 *
 * The HTTP path is the primary write path: the outbox drains from `WorkManager`
 * with no socket open. The WebSocket is a latency optimisation on top.
 */
interface SyncApi {
    /**
     * `POST /lists/{listId}/mutate`. [body] is the exact JSON the outbox stored
     * when the mutation was created — resent byte-for-byte on every retry, so the
     * idempotency key never changes (F5.2).
     */
    suspend fun mutate(
        listId: String,
        body: String,
    ): MutationAck

    /** `GET /lists/{listId}/changes?since=N`. */
    suspend fun changes(
        listId: String,
        since: Long,
    ): CatchUpResponse

    /**
     * `POST /lists/{listId}`. Claims a client-generated list id as owner (F5.1).
     * Claiming a list you already belong to is a no-op, so a retry is harmless.
     */
    suspend fun claimList(listId: String)

    /** `GET /auth/memberships`. */
    suspend fun memberships(): List<Membership>

    /**
     * `POST /lists/{listId}/invite`. Owner only (L3) — a member asking gets a
     * 403, which arrives as [ApiException.Rejected].
     *
     * The token is a short-lived JWT scoped to this one list, distinct in claim
     * shape from an access token so it cannot be replayed as one. What carries it
     * to the other person is a UI concern; the protocol does not care.
     */
    suspend fun createInvite(listId: String): CreateInviteResponse

    /**
     * `POST /invites/accept`, authenticated as the invitee. Accepting one twice
     * is a no-op rather than an error, so a retry is harmless (L3).
     */
    suspend fun acceptInvite(inviteToken: String): AcceptInviteResponse

    /**
     * `POST /devices/token`. Not a sync call, but it belongs to the same
     * transport: it needs the bearer token and the refresh-once-on-401 handling,
     * and a second client for one endpoint would duplicate both.
     *
     * Sent from the sync run rather than from wherever the token arrived, so a
     * token that turns up while the phone is in a dead spot is retried with the
     * same backoff as everything else instead of being lost (M2).
     */
    suspend fun registerPushToken(fcmToken: String)
}

/**
 * Where the caller has to decide between "try again later" and "this will never
 * work", the type says which. [Transport] and [Unavailable] are retried forever
 * with backoff; [Rejected] is a client bug and retrying it is a hot loop.
 */
sealed class ApiException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** No answer at all: no network, DNS, TLS, a dropped socket. */
    class Transport(
        cause: Throwable,
    ) : ApiException("transport failure", cause)

    /** 5xx, or a body that did not parse. The server may be fine in a minute. */
    class Unavailable(
        val status: Int,
        val code: String?,
        cause: Throwable? = null,
    ) : ApiException("server unavailable: $status ${code ?: ""}", cause)

    /** 401. The caller refreshes and retries once; twice means the session is gone. */
    class Unauthorized(
        val code: String?,
    ) : ApiException("unauthorized: ${code ?: ""}")

    /**
     * 4xx that a retry cannot fix — `malformed`, `incomplete-create`,
     * `list-mismatch`, or a 403 for a list this account cannot reach. A request
     * for a list the caller is not a member of returns 403, not 404: membership
     * must not double as an oracle for which list ids exist.
     */
    class Rejected(
        val status: Int,
        val code: String?,
    ) : ApiException("rejected: $status ${code ?: ""}")
}

/**
 * The access token, and the one operation that can produce a new one. Kept behind
 * an interface so [SyncApi] implementations never touch storage themselves.
 */
interface AccessTokens {
    suspend fun current(): String?

    /** Exchanges the refresh token for a new pair. Null when the session is gone. */
    suspend fun refreshed(): String?
}
