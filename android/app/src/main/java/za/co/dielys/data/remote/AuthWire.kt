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

/** `POST /auth/magic/request` (ADR 0005). */
@Serializable
data class RequestMagicLinkRequest(
    val email: String,
)

@Serializable
data class RequestMagicLinkResponse(
    /** Seconds until the link expires — not a timestamp (F5.9). */
    val expiresIn: Long,
    /** Opaque handle for `GET /auth/magic/status` — never the email itself. */
    val requestId: String,
)

/**
 * `GET /auth/magic/status?requestId=…` (ADR 0005 follow-up). Polled every few
 * seconds while "Check your email" is on screen. Never authenticated — there
 * is no session yet — and answers `delivered = false` for a requestId that is
 * wrong, expired, or superseded by a resend, rather than an error.
 */
@Serializable
data class MagicLinkStatusResponse(
    val delivered: Boolean,
)

/**
 * `POST /auth/magic/verify` (ADR 0005). No email — the token alone names the
 * request that minted it.
 */
@Serializable
data class VerifyMagicLinkRequest(
    val token: String,
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

/**
 * `email` scopes the invite (L3): only an account whose own email matches
 * this one may accept it. `listTitle` is display text for the invite email
 * — the server mints invites without ever talking to the list's own DO, so
 * the owner's device, which already has the title, carries it along.
 */
@Serializable
data class CreateInviteRequest(
    val listId: String,
    val email: String,
    val listTitle: String,
)

/**
 * No `inviteToken` here — the server mails the link itself, so this device
 * never needs to hold the bearer token at all.
 */
@Serializable
data class CreateInviteResponse(
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
    /**
     * Where this list sits in *this* account's ordering (F5.5). Per membership,
     * not per list: the two people sharing a list each keep their own order.
     * Null until this account has dragged something, and null sorts last.
     */
    val position: String? = null,
    /** How many people are on this list, this account included. */
    val memberCount: Int = 1,
)

/** `POST /auth/memberships/position` — one list, moved in the caller's own order. */
@Serializable
data class SetListPositionRequest(
    val listId: String,
    val position: String,
)

@Serializable
data class SetListPositionResponse(
    val listId: String,
    val position: String,
)

@Serializable
data class MembershipsResponse(
    val memberships: List<Membership>,
)

object MembershipRole {
    const val OWNER = "owner"
    const val MEMBER = "member"
}

/**
 * One person on a shared list (#60). The email is what names them — there is no
 * display name in this system, only the address somebody signed in with, which
 * is already the thing the owner typed to invite them.
 */
@Serializable
data class ListMember(
    val userId: String,
    val email: String,
    val role: String,
) {
    val isOwner: Boolean get() = role == MembershipRole.OWNER
}

@Serializable
data class ListMembersResponse(
    val members: List<ListMember>,
)
