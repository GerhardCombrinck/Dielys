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
 * `POST /auth/ws-ticket` (ADR 0009). No request type — the caller's own
 * bearer token is the input, nothing else. A browser cannot set
 * `Authorization` on a WebSocket upgrade the way every other request (and
 * OkHttp's upgrade) can, so it exchanges its access token for one of these
 * first and opens `GET /lists/{listId}/ws?ticket=...` instead.
 */
export interface WsTicketResponse {
  ticket: string;
  /** Seconds until the ticket expires — short, and it is single-use besides.
   * Not a timestamp (F5.9). */
  expiresIn: number;
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
  /**
   * The highest seq this list's changelog has reached, as far as the server
   * knows — so a client can skip asking a list for changes when its cursor is
   * already there (PROTOCOL.md "Which lists have changed").
   *
   * A lower bound, and null when the server has no record yet (a list with no
   * write since this field was added). Null means "ask", never "nothing new".
   */
  maxSeq: number | null;
  /**
   * Which kinds of change on this list this member wants a notification for
   * (PROTOCOL.md "Notifications for a list"). Empty — the default for every
   * membership, a new one included — means none. Per member, like `position`:
   * the two people on a shared list each choose their own.
   *
   * An older server's answer has no field, which a client treats as empty.
   */
  notify: NotifyEvent[];
}

/**
 * A kind of change to a task somebody else made on a shared list:
 *
 * - `added` — a task this client had never seen.
 * - `checked` — ticked off, or unticked again.
 * - `deleted` — tombstoned (F5.3).
 * - `updated` — renamed, or starred/unstarred.
 *
 * A move (position only) is none of them, and never notifies. A reader that
 * meets a value it does not know ignores it rather than rejecting the whole
 * membership (F2).
 */
export type NotifyEvent = "added" | "checked" | "deleted" | "updated";

/** Every `NotifyEvent`, in the order a settings screen lists them. */
export const NOTIFY_EVENTS: readonly NotifyEvent[] = ["added", "checked", "deleted", "updated"];

/**
 * `POST /auth/memberships/notify` — replaces the caller's notification choice
 * for one list. `events` is the whole new set, not a delta; `[]` turns
 * notifications off. Duplicates and unknown values are `malformed`.
 *
 * No idempotency key, for the reason `SetListPositionRequest` gives: last write
 * wins on a value only the caller owns, so a retried identical body is the same
 * state (F5.2 has nothing to protect).
 */
export interface SetListNotifyRequest {
  listId: string;
  events: NotifyEvent[];
}

export interface SetListNotifyResponse {
  listId: string;
  events: NotifyEvent[];
}

export interface MembershipsResponse {
  memberships: Membership[];
}

/**
 * One person on a shared list, for the "shared with" sheet (#60).
 *
 * The email is what names them: there is no display name anywhere in this
 * system, only the address somebody signed in with. That is already known to
 * everyone on the list — the owner typed it to invite them — so showing it back
 * to the people on that list tells them nothing they did not already have.
 */
export interface ListMember {
  userId: string;
  email: string;
  role: MembershipRole;
}

/**
 * `GET /lists/{listId}/members` — who is on this list. Any member may ask; a
 * non-member gets the same `forbidden` every other list route gives them, never
 * a 404, so this cannot be used to ask which list ids exist (L3).
 */
export interface ListMembersResponse {
  members: ListMember[];
}

/**
 * `DELETE /lists/{listId}/members/{userId}` — takes one person off the list.
 *
 * Two callers are allowed, and no others (L3): the owner removing somebody
 * else, or anybody removing themselves. The owner may not remove themselves,
 * because a list with no owner is one nobody can ever share again — they delete
 * the list instead.
 *
 * Only the membership goes. The list's own rows are untouched, and so is the
 * copy already on the removed person's phone: this decides who may reach the
 * list from now on, not what is already on somebody's device.
 */
export interface RemoveMemberResponse {
  listId: string;
  userId: string;
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

/**
 * The account's background-sync preference (ADR 0010): whether periodic
 * background sync runs at all, and how often. Effect is mobile-only — the
 * web client has no background worker to schedule (web/AGENTS.md) — but the
 * setting itself is held here, server-side, so it can be read and changed
 * from either client, not just the phone it actually governs.
 */
export interface SyncSettings {
  enabled: boolean;
  intervalMinutes: number;
}

/** The floor Android's own `PeriodicWorkRequest` enforces regardless —
 * checked here too so a bad value never reaches it. */
export const MIN_SYNC_INTERVAL_MINUTES = 15;
/** A week — generous, but still a bound (F3): nothing needs to sync less
 * often than that for a value this cheap to fetch. */
export const MAX_SYNC_INTERVAL_MINUTES = 10_080;
export const DEFAULT_SYNC_INTERVAL_MINUTES = 30;

/**
 * `GET /auth/sync-settings` → `SyncSettings`.
 *
 * `PATCH /auth/sync-settings`: `SyncSettingsPatch` → `SyncSettings`, the
 * resulting state. Whichever field is present is the one being changed — the
 * same partial-patch shape `ListPatch`/`TaskPatch` use, but without an
 * idempotency key: like `SetListPositionRequest`, this is last-write-wins on
 * a value the caller alone owns, so a retried identical body is the same
 * state and F5.2 does not apply.
 */
export interface SyncSettingsPatch {
  enabled?: boolean;
  intervalMinutes?: number;
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

/**
 * `POST /account/deletion/request` (ADR 0007, "From the web"). For someone who
 * no longer has the app: a confirmation link is mailed to the address if it
 * has an account, and the response is the same whether or not it does.
 */
export interface RequestAccountDeletionRequest {
  email: string;
}

export interface RequestAccountDeletionResponse {
  /** Seconds until a mailed link expires. Not a timestamp (F5.9). */
  expiresIn: number;
}

/**
 * `POST /account/deletion/confirm`. The token from the mailed link, and
 * nothing else: holding it is the proof of the inbox. Answered `204` once the
 * account is erased; `invalid-token` or `token-expired` otherwise.
 */
export interface ConfirmAccountDeletionRequest {
  token: string;
}

/**
 * Digits in the code mailed beside every magic link (ADR 0008): `997218`.
 * About 20 bits, which is why wrong codes are also limited per address.
 */
export const MAGIC_CODE_LENGTH = 6;

/**
 * `POST /auth/magic/verify-code` (ADR 0008). The typed alternative to tapping
 * the link: the same email, the code from it, and this device. Unlike
 * `VerifyMagicLinkRequest` the email is needed — a code is short enough to
 * collide across addresses, so it is looked up under the address it was sent
 * to. Answers a `TokenPair`, exactly as the link does.
 *
 * `code` is forgiving on the wire: spaces and dashes are ignored. Too many
 * wrong codes for one address in a day answers `rate-limited`, even for the
 * right code.
 */
export interface VerifyMagicCodeRequest {
  email: string;
  code: string;
  deviceId: string;
}
