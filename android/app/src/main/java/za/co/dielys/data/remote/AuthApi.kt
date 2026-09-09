package za.co.dielys.data.remote

/**
 * Registration, login and refresh. Separate from [SyncApi] because these are the
 * calls that do not carry a bearer token — the client has none yet, or the one it
 * has is dead.
 *
 * An interface for the same reason [SyncApi] is one: the result mapping in
 * `SessionRepository` is where a wrong answer would be silently wrong, and it is
 * worth testing on the JVM against a fake rather than a socket (H1).
 */
interface AuthApi {
    /**
     * `POST /auth/login`.
     *
     * `invalid-credentials` covers a wrong password, a wrong email, and an
     * account that does not exist, deliberately: three distinct codes would be an
     * account-enumeration oracle. Nothing above this should try to tell them
     * apart.
     */
    suspend fun login(
        email: String,
        password: String,
        deviceId: String,
    ): TokenPair

    /**
     * `POST /auth/register` (L2, ADR 0004). Answers with a session, so a new
     * account is a signed-in account.
     *
     * This one *can* be told apart from a wrong password: `already-exists` means
     * the email is taken, and there is no honest way for it not to. Accepted, and
     * rate limited server-side rather than papered over here.
     */
    suspend fun register(
        email: String,
        password: String,
        deviceId: String,
    ): TokenPair

    /**
     * `POST /auth/refresh`. Refresh tokens rotate on every use, so the answer
     * carries a new one and the presented token is spent — presenting it again is
     * treated as theft and revokes every session for the user.
     */
    suspend fun refresh(
        refreshToken: String,
        deviceId: String,
    ): TokenPair
}
