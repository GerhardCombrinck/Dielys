/**
 * Authentication wire types. Declared here first, like everything else that
 * crosses the boundary (F1). See docs/adr/0002-authentication.md and
 * CODE_STANDARD.md L1-L3.
 *
 * These travel over plain HTTP requests rather than the sync WebSocket — the
 * client needs a token before it can open a socket at all.
 */

export const MAX_EMAIL_LENGTH = 320; // RFC 5321 local@domain maximum

/**
 * A floor, not a policy. The household's rule is generated passwords from a
 * password manager, which is what actually makes the PBKDF2 work factor a
 * non-issue — see the note on PASSWORD_ITERATIONS in server/src/auth/password.ts.
 */
export const MIN_PASSWORD_LENGTH = 10;

/**
 * HMAC folds an over-long key by hashing it, so a megabyte password costs the
 * same to verify as a long one — but it still has to be read, parsed and moved
 * around, so it is bounded like everything else at the boundary (F3).
 */
export const MAX_PASSWORD_LENGTH = 1024;

export interface LoginRequest {
  email: string;
  password: string;
  /**
   * The same device identifier used for F5.4 conflict tie-breaks (L1).
   * Introduced once, used for both auth and ordering.
   */
  deviceId: string;
}

/**
 * `POST /auth/register` (L2, ADR 0004). Same shape as a login, because it ends
 * the same way: a successful registration returns the `TokenPair` login returns
 * and the caller is signed in. A second round trip to log in afterwards would be
 * two chances to fail for one intent.
 *
 * Unlike a login, the password here IS strength-checked — see
 * `MIN_PASSWORD_LENGTH`.
 */
export interface RegisterRequest {
  email: string;
  password: string;
  deviceId: string;
}

export interface RefreshRequest {
  refreshToken: string;
  deviceId: string;
}

export interface TokenPair {
  accessToken: string;
  /**
   * Rotated on every use (L1) — the value returned here replaces the one that
   * was presented, and presenting the old one again is treated as theft.
   */
  refreshToken: string;
  /** Seconds until `accessToken` expires. Not a timestamp: client clocks are
   * not trusted (F5.9), so the client counts down from receipt. */
  expiresIn: number;
  userId: string;
}

/**
 * `email` scopes the invite (L3): only an account whose own email matches
 * this one, normalized, may accept it. `listTitle` is display text for the
 * invite email — `UsersRoom`, where invites are minted, never talks to
 * `ListRoom`, where titles live, so the owner's device (which already has
 * it) carries it along rather than the server fetching cross-DO for it.
 */
export interface CreateInviteRequest {
  listId: string;
  email: string;
  listTitle: string;
}

/**
 * No `inviteToken` here on purpose — the server emails the link itself now,
 * so the inviter's own device never needs to hold the bearer token at all.
 */
export interface CreateInviteResponse {
  expiresIn: number;
}

export interface AcceptInviteRequest {
  inviteToken: string;
}

export interface AcceptInviteResponse {
  listId: string;
  role: MembershipRole;
  /** True when this invite had already been accepted — a no-op, not an error (L3). */
  alreadyMember: boolean;
}

export type MembershipRole = "owner" | "member";

export interface Membership {
  listId: string;
  role: MembershipRole;
  /**
   * Where this list sits in *this member's* ordering, as the same fractional
   * index tasks use (F5.5). Per membership rather than per list on purpose: the
   * two people on a shared list each keep their own order, so one dragging
   * "Inkopies" to the top does not reorder the other's screen.
   *
   * Null until that member has ordered anything. An unordered list sorts after
   * every ordered one, oldest membership first, so a list somebody was just
   * invited to appears at the bottom instead of jumping into the middle.
   */
  position: string | null;
  /**
   * How many people are on this list, this caller included. A client uses
   * this to tell a shared list from a solo one — sync status is only
   * interesting once someone else can make the local copy go stale.
   */
  memberCount: number;
}

export interface MembershipsResponse {
  memberships: Membership[];
}

/**
 * `POST /auth/memberships/position` — moves one list in the caller's own
 * ordering. The client computes the key from the neighbours it dropped between,
 * exactly as it does for a task, so a move writes one row and never renumbers
 * the lists around it (F5.5).
 *
 * No idempotency key: the write is last-write-wins on a single column that the
 * caller alone owns, so a retry of the same body is the same state. That is not
 * true of a list mutation, which is why those still carry one (F5.2).
 */
export interface SetListPositionRequest {
  listId: string;
  position: string;
}

export interface SetListPositionResponse {
  listId: string;
  position: string;
}

export type AuthErrorCode =
  /** Wrong email, wrong password, or no such account — deliberately one code,
   * so the response cannot be used to enumerate which emails exist. */
  | "invalid-credentials"
  | "token-expired"
  /** A refresh token was presented after it had already been rotated. Every
   * session for that user is revoked (L1). */
  | "token-reused"
  | "forbidden"
  | "not-found"
  /**
   * Registration answers this when the email is taken. It is an
   * account-enumeration oracle and there is no honest way for it not to be —
   * accepted and bounded by the rate limit, see ADR 0004. Login MUST NOT use it.
   */
  | "already-exists"
  /** Too many attempts in the window (L2, ADR 0004). Answered with a 429. */
  | "rate-limited"
  /** No magic-link token matches what was presented — wrong, already spent,
   * or nothing was ever requested for the email it names (ADR 0005). One
   * code for all three, same enumeration reasoning as `invalid-credentials`. */
  | "invalid-token"
  /** The deployment has no working mail sender configured (ADR 0005) — fails
   * closed, the same shape as an unusable JWT_SIGNING_KEY. */
  | "internal";

/** `POST /auth/magic/request` (ADR 0005). */
export interface RequestMagicLinkRequest {
  email: string;
}

export interface RequestMagicLinkResponse {
  /** Seconds until the link expires. Not a timestamp — client clocks are not
   * trusted (F5.9). */
  expiresIn: number;
  /**
   * Opaque handle for `GET /auth/magic/status` — never the email itself, so
   * polling delivery status cannot become a second way to ask "does this
   * address have anything pending" the way echoing the email back would.
   */
  requestId: string;
}

/**
 * `GET /auth/magic/status?requestId=…` (ADR 0005 follow-up). Polled by the
 * client every few seconds while "Check your email" is on screen, so it can
 * say "delivered" instead of leaving a fixed "a few minutes" estimate up
 * regardless of how the send actually went. Never authenticated — there is
 * no session yet — and answers `delivered: false` for a requestId that is
 * wrong, expired, or superseded by a resend rather than an error, the same
 * enumeration reasoning `invalid-token` uses elsewhere in this file.
 */
export interface MagicLinkStatusResponse {
  delivered: boolean;
}

/**
 * `POST /auth/magic/verify` (ADR 0005). No email here on purpose — the token
 * alone names the request that minted it, and asking the caller to also
 * supply the email would just be a second value that has to agree with the
 * first for no reason. A right token creates the account on first use and
 * signs in on every use after, the same collapse `RegisterRequest` already
 * uses for a first-time caller.
 */
export interface VerifyMagicLinkRequest {
  token: string;
  deviceId: string;
}
