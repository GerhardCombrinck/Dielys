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
export const MIN_PASSWORD_LENGTH = 12;

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
}

export interface MembershipsResponse {
  memberships: Membership[];
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
  | "already-exists";
