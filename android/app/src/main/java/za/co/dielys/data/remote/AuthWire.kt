package za.co.dielys.data.remote

import kotlinx.serialization.Serializable

/**
 * Authentication wire types, mirroring `protocol/src/auth.ts`. These travel over
 * plain HTTP — the client needs a token before it can open a socket at all.
 *
 * Every request carries the access token as `Authorization: Bearer <jwt>`,
 * including the WebSocket upgrade: L3 requires the Worker to authorize before
 * anything reaches a `ListRoom`, and the Worker cannot see messages sent after the
 * socket is established.
 */

const val MAX_EMAIL_LENGTH = 320
const val MIN_PASSWORD_LENGTH = 10
const val MAX_PASSWORD_LENGTH = 1024

@Serializable
data class LoginRequest(
    val email: String,
    val password: String,
    /** The same identifier used for F5.4 conflict tie-breaks (L1). */
    val deviceId: String,
)

/**
 * `POST /auth/register` (L2, ADR 0004). Same shape as a login and the same
 * answer — a [TokenPair] — because registering signs you in. Unlike a login,
 * [MIN_PASSWORD_LENGTH] is enforced here.
 */
@Serializable
data class RegisterRequest(
    val email: String,
    val password: String,
    val deviceId: String,
)

@Serializable
data class RefreshRequest(
    val refreshToken: String,
    val deviceId: String,
)

@Serializable
data class TokenPair(
    val accessToken: String,
    /** Rotated on every use (L1). Presenting the old one again is treated as theft. */
    val refreshToken: String,
    /** Seconds until [accessToken] expires — not a timestamp, because client clocks
     * are not trusted (F5.9). The client counts down from receipt. */
    val expiresIn: Long,
    val userId: String,
)

@Serializable
data class CreateInviteRequest(
    val listId: String,
)

@Serializable
data class CreateInviteResponse(
    val inviteToken: String,
    val expiresIn: Long,
)

@Serializable
data class AcceptInviteRequest(
    val inviteToken: String,
)

@Serializable
data class AcceptInviteResponse(
    val listId: String,
    val role: String,
    /** True when this invite had already been accepted — a no-op, not an error (L3). */
    val alreadyMember: Boolean,
)

@Serializable
data class Membership(
    val listId: String,
    val role: String,
)

@Serializable
data class MembershipsResponse(
    val memberships: List<Membership>,
)

object MembershipRole {
    const val OWNER = "owner"
    const val MEMBER = "member"
}
