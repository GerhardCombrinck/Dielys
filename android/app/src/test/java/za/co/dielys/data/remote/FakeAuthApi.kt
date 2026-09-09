package za.co.dielys.data.remote

import java.io.IOException

/**
 * The auth routes without a socket. Holds one account at a time, which is all
 * the result mapping above it needs in order to be told apart.
 */
class FakeAuthApi : AuthApi {
    /** Flip to false to make every call fail the way no network fails. */
    var online: Boolean = true

    /** The code the next call is rejected with, or null to let it through. */
    var rejectWith: String? = null

    /** The accounts that exist, email to password. Registering one again is a 409. */
    val accounts: MutableMap<String, String> = mutableMapOf()

    /** Every email handed to [register] or [login], in order, as sent. */
    val seen: MutableList<String> = mutableListOf()

    private var issued = 0

    override suspend fun login(
        email: String,
        password: String,
        deviceId: String,
    ): TokenPair {
        gate()
        seen += email
        if (accounts[email] != password) {
            // One answer for a wrong password, a wrong email and an account that
            // does not exist — the server cannot tell them apart on purpose.
            throw ApiException.Rejected(status = 401, code = ErrorCode.INVALID_CREDENTIALS)
        }
        return pair()
    }

    override suspend fun register(
        email: String,
        password: String,
        deviceId: String,
    ): TokenPair {
        gate()
        seen += email
        if (accounts.putIfAbsent(email, password) != null) {
            throw ApiException.Rejected(status = 409, code = ErrorCode.ALREADY_EXISTS)
        }
        return pair()
    }

    override suspend fun refresh(
        refreshToken: String,
        deviceId: String,
    ): TokenPair {
        gate()
        return pair()
    }

    private fun gate() {
        rejectWith?.let { code ->
            rejectWith = null
            throw ApiException.Rejected(status = 400, code = code)
        }
        if (!online) throw ApiException.Transport(IOException("no network"))
    }

    private fun pair(): TokenPair {
        issued++
        return TokenPair(
            accessToken = "access-$issued",
            refreshToken = "refresh-$issued",
            expiresIn = 900,
            userId = "user-1",
        )
    }
}
