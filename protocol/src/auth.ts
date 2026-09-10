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

export interface CreateInviteRequest {
  listId: string;
}

export interface CreateInviteResponse {
  inviteToken: string;
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
  | "rate-limited";
