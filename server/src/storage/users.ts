/**
 * SQL against the UsersRoom DO's SQLite. No business rules here (D1) — it
 * reads and writes rows; it does not decide whether a password is right or
 * whether a token may be rotated.
 */
import {
  type ListMember,
  type Membership,
  type MembershipRole,
  NOTIFY_EVENTS,
  type NotifyEvent,
  type SyncSettings,
} from "@dielys/protocol";
import type { PasswordHash } from "../auth/password.js";

export interface UserRow {
  id: string;
  email: string;
  password: PasswordHash;
  createdAt: string;
}

/** One phone. See migrations/users/0002_devices.sql for why it is its own row. */
export interface DeviceRow {
  deviceId: string;
  userId: string;
  fcmToken: string;
}

/** A device in a list's wake fan-out, and whether its account has asked to be
 * notified about that list — which is what decides the push's priority. */
export interface ListDeviceRow extends DeviceRow {
  notify: boolean;
}

export interface RefreshTokenRow {
  tokenHash: string;
  userId: string;
  deviceId: string;
  issuedAt: string;
  expiresAt: string;
  usedAt: string | null;
}

export function insertUser(sql: SqlStorage, user: UserRow): void {
  sql.exec(
    `INSERT INTO users (id, email, password_hash, password_salt, password_iterations, created_at)
     VALUES (?, ?, ?, ?, ?, ?)`,
    user.id,
    user.email,
    user.password.hash,
    user.password.salt,
    user.password.iterations,
    user.createdAt,
  );
}

export function selectUserByEmail(sql: SqlStorage, email: string): UserRow | null {
  return firstUser(
    sql.exec(
      `SELECT id, email, password_hash, password_salt, password_iterations, created_at
         FROM users WHERE email = ?`,
      email,
    ),
  );
}

export function selectUserById(sql: SqlStorage, id: string): UserRow | null {
  return firstUser(
    sql.exec(
      `SELECT id, email, password_hash, password_salt, password_iterations, created_at
         FROM users WHERE id = ?`,
      id,
    ),
  );
}

export function updateUserPassword(sql: SqlStorage, userId: string, password: PasswordHash): void {
  sql.exec(
    `UPDATE users SET password_hash = ?, password_salt = ?, password_iterations = ? WHERE id = ?`,
    password.hash,
    password.salt,
    password.iterations,
    userId,
  );
}

/**
 * The account row, gone (ADR 0007). A real `DELETE`, not a tombstone: F5.3 is
 * about list entities that have to sync, and an erased account is only erased
 * if its email address is no longer here.
 */
export function deleteUser(sql: SqlStorage, userId: string): void {
  sql.exec("DELETE FROM users WHERE id = ?", userId);
}

export function countUsers(sql: SqlStorage): number {
  const row = sql.exec("SELECT COUNT(*) AS n FROM users").one();
  return Number(row.n);
}

/** Newest account first (#84) — "who signed up" reads top-down like an
 *  activity feed, not alphabetically. */
export function selectAccountsNewestFirst(sql: SqlStorage): { userId: string; email: string }[] {
  return [...sql.exec("SELECT id, email FROM users ORDER BY created_at DESC")].map((row) => ({
    userId: String(row.id),
    email: String(row.email),
  }));
}

/** `null` for a user id that does not exist — the caller (`UsersRoom`) turns
 * that into `not-found`; this layer just reports what it saw (D1). */
export function selectSyncSettings(sql: SqlStorage, userId: string): SyncSettings | null {
  const rows = [
    ...sql.exec(`SELECT sync_enabled, sync_interval_minutes FROM users WHERE id = ?`, userId),
  ];
  const row = rows[0];
  if (row === undefined) return null;
  return {
    enabled: Number(row.sync_enabled) !== 0,
    intervalMinutes: Number(row.sync_interval_minutes),
  };
}

/** Always writes both columns — the caller has already merged the patch onto
 * the current row, so this is a plain overwrite, not a partial one. */
export function updateSyncSettings(sql: SqlStorage, userId: string, settings: SyncSettings): void {
  sql.exec(
    `UPDATE users SET sync_enabled = ?, sync_interval_minutes = ? WHERE id = ?`,
    settings.enabled ? 1 : 0,
    settings.intervalMinutes,
    userId,
  );
}

function firstUser(cursor: SqlStorageCursor<Record<string, SqlStorageValue>>): UserRow | null {
  const rows = [...cursor];
  const row = rows[0];
  if (row === undefined) return null;
  return {
    id: String(row.id),
    email: String(row.email),
    password: {
      hash: String(row.password_hash),
      salt: String(row.password_salt),
      iterations: Number(row.password_iterations),
    },
    createdAt: String(row.created_at),
  };
}

export function insertRefreshToken(sql: SqlStorage, row: RefreshTokenRow): void {
  sql.exec(
    `INSERT INTO refresh_tokens (token_hash, user_id, device_id, issued_at, expires_at, used_at)
     VALUES (?, ?, ?, ?, ?, ?)`,
    row.tokenHash,
    row.userId,
    row.deviceId,
    row.issuedAt,
    row.expiresAt,
    row.usedAt,
  );
}

export function selectRefreshToken(sql: SqlStorage, tokenHash: string): RefreshTokenRow | null {
  const rows = [
    ...sql.exec(
      `SELECT token_hash, user_id, device_id, issued_at, expires_at, used_at
         FROM refresh_tokens WHERE token_hash = ?`,
      tokenHash,
    ),
  ];
  const row = rows[0];
  if (row === undefined) return null;
  return {
    tokenHash: String(row.token_hash),
    userId: String(row.user_id),
    deviceId: String(row.device_id),
    issuedAt: String(row.issued_at),
    expiresAt: String(row.expires_at),
    usedAt: row.used_at === null ? null : String(row.used_at),
  };
}

/** Marks a token spent. The row stays so a later replay is still detectable. */
export function markRefreshTokenUsed(sql: SqlStorage, tokenHash: string, usedAt: string): void {
  sql.exec("UPDATE refresh_tokens SET used_at = ? WHERE token_hash = ?", usedAt, tokenHash);
}

/** Every session for one user, gone. The response to a detected replay (L1). */
export function deleteRefreshTokensForUser(sql: SqlStorage, userId: string): number {
  const before = countRefreshTokens(sql, userId);
  sql.exec("DELETE FROM refresh_tokens WHERE user_id = ?", userId);
  return before;
}

export function countRefreshTokens(sql: SqlStorage, userId: string): number {
  const row = sql.exec("SELECT COUNT(*) AS n FROM refresh_tokens WHERE user_id = ?", userId).one();
  return Number(row.n);
}

/**
 * Housekeeping. Expired and long-spent rows are only useful for replay
 * detection while a replay is still plausible; past that they are dead weight
 * in a DO that is billed on stored bytes.
 */
export function deleteExpiredRefreshTokens(sql: SqlStorage, before: string): void {
  sql.exec("DELETE FROM refresh_tokens WHERE expires_at < ?", before);
}

export interface WsTicketRow {
  ticketHash: string;
  userId: string;
  deviceId: string;
  issuedAt: string;
  expiresAt: string;
  usedAt: string | null;
}

export function insertWsTicket(sql: SqlStorage, row: WsTicketRow): void {
  sql.exec(
    `INSERT INTO ws_tickets (ticket_hash, user_id, device_id, issued_at, expires_at, used_at)
     VALUES (?, ?, ?, ?, ?, ?)`,
    row.ticketHash,
    row.userId,
    row.deviceId,
    row.issuedAt,
    row.expiresAt,
    row.usedAt,
  );
}

export function selectWsTicket(sql: SqlStorage, ticketHash: string): WsTicketRow | null {
  const rows = [
    ...sql.exec(
      `SELECT ticket_hash, user_id, device_id, issued_at, expires_at, used_at
         FROM ws_tickets WHERE ticket_hash = ?`,
      ticketHash,
    ),
  ];
  const row = rows[0];
  if (row === undefined) return null;
  return {
    ticketHash: String(row.ticket_hash),
    userId: String(row.user_id),
    deviceId: String(row.device_id),
    issuedAt: String(row.issued_at),
    expiresAt: String(row.expires_at),
    usedAt: row.used_at === null ? null : String(row.used_at),
  };
}

/** Marks a ticket spent. The row stays, on the same reasoning as a refresh
 * token, though nothing currently reads a used row back. */
export function markWsTicketUsed(sql: SqlStorage, ticketHash: string, usedAt: string): void {
  sql.exec("UPDATE ws_tickets SET used_at = ? WHERE ticket_hash = ?", usedAt, ticketHash);
}

/** Housekeeping, same reasoning as [deleteExpiredRefreshTokens]. */
export function deleteExpiredWsTickets(sql: SqlStorage, before: string): void {
  sql.exec("DELETE FROM ws_tickets WHERE expires_at < ?", before);
}

export function insertMembership(
  sql: SqlStorage,
  userId: string,
  listId: string,
  role: MembershipRole,
  createdAt: string,
): void {
  // L3: accepting an invite twice is a no-op, not an error, and must not
  // create a duplicate row. The role of an existing membership is not
  // downgraded by a later plain invite.
  sql.exec(
    `INSERT INTO memberships (user_id, list_id, role, created_at) VALUES (?, ?, ?, ?)
     ON CONFLICT(user_id, list_id) DO NOTHING`,
    userId,
    listId,
    role,
    createdAt,
  );
}

export function selectMembership(
  sql: SqlStorage,
  userId: string,
  listId: string,
): Membership | null {
  const rows = [
    ...sql.exec(
      `SELECT m.list_id, m.role, m.position, m.notify_events, h.max_seq,
              (SELECT COUNT(*) FROM memberships m2 WHERE m2.list_id = m.list_id) AS member_count
         FROM memberships m
         LEFT JOIN list_heads h ON h.list_id = m.list_id
        WHERE m.user_id = ? AND m.list_id = ?`,
      userId,
      listId,
    ),
  ];
  const row = rows[0];
  if (row === undefined) return null;
  return toMembership(row);
}

/** Every list anyone still has a membership on (#84) — the full set this
 *  room can name, for the admin stats route to fan out to. */
export function selectDistinctListIds(sql: SqlStorage): string[] {
  return [...sql.exec("SELECT DISTINCT list_id FROM memberships")].map((row) =>
    String(row.list_id),
  );
}

/** How many people are on a list at all. Zero means it is unclaimed. */
export function countListMembers(sql: SqlStorage, listId: string): number {
  const row = sql.exec("SELECT COUNT(*) AS n FROM memberships WHERE list_id = ?", listId).one();
  return Number(row.n);
}

/**
 * The caller's lists in the caller's own order (PROTOCOL.md "Ordering the
 * lists").
 *
 * `COALESCE(position, '~')` puts the unordered ones last: '~' is 0x7E, above
 * every character the base-62 position alphabet uses, so no real key can sort
 * past it. Among those, oldest membership first — a list you were invited to
 * this morning belongs at the bottom, not in the middle of an order somebody
 * arranged. BINARY collation throughout, like every other position comparison.
 */
export function selectMemberships(sql: SqlStorage, userId: string): Membership[] {
  const cursor = sql.exec(
    `SELECT m.list_id, m.role, m.position, m.notify_events, h.max_seq,
            (SELECT COUNT(*) FROM memberships m2 WHERE m2.list_id = m.list_id) AS member_count
       FROM memberships m
       LEFT JOIN list_heads h ON h.list_id = m.list_id
      WHERE m.user_id = ?
      ORDER BY COALESCE(m.position, '~'), m.created_at, m.list_id`,
    userId,
  );
  return [...cursor].map(toMembership);
}

/**
 * Everyone on one list, for the "shared with" sheet (#60).
 *
 * The email comes from the users table rather than the membership, because
 * there is no display name anywhere in this system — the address somebody
 * signed in with is the only thing that names them to the other person on
 * the list.
 *
 * Owner first, then oldest membership: the owner is the one answer somebody
 * opening this is most likely looking for, and the rest in the order they
 * joined is the only order that does not change under them.
 */
export function selectListMembers(sql: SqlStorage, listId: string): ListMember[] {
  const cursor = sql.exec(
    `SELECT m.user_id, m.role, u.email
       FROM memberships m
       JOIN users u ON u.id = m.user_id
      WHERE m.list_id = ?
      ORDER BY CASE m.role WHEN 'owner' THEN 0 ELSE 1 END, m.created_at, m.user_id`,
    listId,
  );
  return [...cursor].map((row) => ({
    userId: String(row.user_id),
    email: String(row.email),
    role: String(row.role) as MembershipRole,
  }));
}

/**
 * Takes one person off one list. Returns false when there was no such
 * membership, which the route answers the same way it answers "not yours" —
 * never a 404, so this cannot be used to ask who is on a list you are not on
 * (L3), the same rule [updateMembershipPosition] follows.
 *
 * The list's own rows are untouched. A membership is this server's record of
 * who may reach the list; the list itself belongs to its owner either way, and
 * the copy already on the removed person's phone is theirs — this is about
 * access from here on, not about reaching into somebody's device.
 */
export function deleteMembership(sql: SqlStorage, userId: string, listId: string): boolean {
  const rows = [
    ...sql.exec(
      "DELETE FROM memberships WHERE user_id = ? AND list_id = ? RETURNING list_id",
      userId,
      listId,
    ),
  ];
  return rows.length > 0;
}

/** Every list one user is on and their role on it, unordered — for walking
 * them when the account goes (ADR 0007), not for showing to anybody. */
export function selectRolesForUser(
  sql: SqlStorage,
  userId: string,
): Array<{ listId: string; role: MembershipRole }> {
  const cursor = sql.exec("SELECT list_id, role FROM memberships WHERE user_id = ?", userId);
  return [...cursor].map((row) => ({
    listId: String(row.list_id),
    role: String(row.role) as MembershipRole,
  }));
}

/**
 * Who on a list, other than [excludingUserId], has been on it longest — the
 * member ownership passes to when the owner's account is deleted (ADR 0007).
 * `user_id` breaks a tie, so the answer never depends on row order.
 */
export function selectLongestOtherMember(
  sql: SqlStorage,
  listId: string,
  excludingUserId: string,
): string | null {
  const rows = [
    ...sql.exec(
      `SELECT user_id FROM memberships
        WHERE list_id = ? AND user_id != ?
        ORDER BY created_at, user_id
        LIMIT 1`,
      listId,
      excludingUserId,
    ),
  ];
  const row = rows[0];
  return row === undefined ? null : String(row.user_id);
}

export function updateMembershipRole(
  sql: SqlStorage,
  userId: string,
  listId: string,
  role: MembershipRole,
): void {
  sql.exec(
    "UPDATE memberships SET role = ? WHERE user_id = ? AND list_id = ?",
    role,
    userId,
    listId,
  );
}

export function deleteMembershipsForUser(sql: SqlStorage, userId: string): void {
  sql.exec("DELETE FROM memberships WHERE user_id = ?", userId);
}

/** A list nobody is on any more has no head worth recording (ADR 0007). */
export function deleteListHead(sql: SqlStorage, listId: string): void {
  sql.exec("DELETE FROM list_heads WHERE list_id = ?", listId);
}

/**
 * Moves one list in one member's order. Returns false when the caller is not on
 * that list, which the route answers as a 403 — never a 404, so this cannot be
 * used to ask which list ids exist (L3).
 *
 * One row, never a renumbering of the others: that is the whole point of a
 * fractional index (F5.5), and it is what lets two of the caller's own devices
 * reorder while offline and merge rather than fight.
 */
export function updateMembershipPosition(
  sql: SqlStorage,
  userId: string,
  listId: string,
  position: string,
): boolean {
  const rows = [
    ...sql.exec(
      `UPDATE memberships SET position = ? WHERE user_id = ? AND list_id = ?
       RETURNING list_id`,
      position,
      userId,
      listId,
    ),
  ];
  return rows.length > 0;
}

function toMembership(row: Record<string, SqlStorageValue>): Membership {
  return {
    listId: String(row.list_id),
    // Written by insertMembership from a MembershipRole; SQLite has no enum.
    role: String(row.role) as MembershipRole,
    position: row.position === null || row.position === undefined ? null : String(row.position),
    memberCount: Number(row.member_count),
    maxSeq: row.max_seq === null || row.max_seq === undefined ? null : Number(row.max_seq),
    notify: parseNotifyEvents(row.notify_events),
  };
}

/**
 * Replaces one member's notification choice for one list. Same shape as
 * [updateMembershipPosition]: false when there is no such membership, which the
 * caller answers as 403 (L3). `events` is already validated and de-duplicated.
 */
export function updateMembershipNotify(
  sql: SqlStorage,
  userId: string,
  listId: string,
  events: readonly NotifyEvent[],
): boolean {
  const rows = [
    ...sql.exec(
      `UPDATE memberships SET notify_events = ? WHERE user_id = ? AND list_id = ?
       RETURNING list_id`,
      formatNotifyEvents(events),
      userId,
      listId,
    ),
  ];
  return rows.length > 0;
}

/** Stored in the order the protocol lists them, whatever order they came in,
 * so the same set is always the same string. */
function formatNotifyEvents(events: readonly NotifyEvent[]): string {
  return NOTIFY_EVENTS.filter((event) => events.includes(event)).join(",");
}

/** A value this build does not know is dropped rather than passed on — a
 * later migration can add a kind without an older reader choking on it. */
function parseNotifyEvents(raw: SqlStorageValue | undefined): NotifyEvent[] {
  if (typeof raw !== "string" || raw === "") return [];
  const stored = raw.split(",");
  return NOTIFY_EVENTS.filter((event) => stored.includes(event));
}

/**
 * Moves a list's recorded head forward to `seq`, never back.
 *
 * `MAX` rather than a plain overwrite because the calls that bring these are
 * fire-and-forget and can land out of order: seq 12's report arriving after
 * seq 13's must not make a client think 13 is still to come — worse, that it
 * already has everything once its cursor reaches 12.
 */
export function recordListHead(sql: SqlStorage, listId: string, seq: number): void {
  sql.exec(
    `INSERT INTO list_heads (list_id, max_seq) VALUES (?, ?)
     ON CONFLICT(list_id) DO UPDATE SET max_seq = MAX(max_seq, excluded.max_seq)`,
    listId,
    seq,
  );
}

/**
 * Files this device's FCM token under its device id, replacing whatever was
 * there. `ON CONFLICT` on the primary key rather than an INSERT-or-UPDATE pair:
 * a token refresh and a sign-in as a different user are the same write, and
 * both have to land as one statement.
 */
export function upsertDevice(sql: SqlStorage, device: DeviceRow, updatedAt: string): void {
  sql.exec(
    `INSERT INTO devices (device_id, user_id, fcm_token, updated_at) VALUES (?, ?, ?, ?)
     ON CONFLICT(device_id) DO UPDATE SET
       user_id = excluded.user_id,
       fcm_token = excluded.fcm_token,
       updated_at = excluded.updated_at`,
    device.deviceId,
    device.userId,
    device.fcmToken,
    updatedAt,
  );
}

export function deleteDevice(sql: SqlStorage, deviceId: string): void {
  sql.exec("DELETE FROM devices WHERE device_id = ?", deviceId);
}

export function deleteDevicesForUser(sql: SqlStorage, userId: string): void {
  sql.exec("DELETE FROM devices WHERE user_id = ?", userId);
}

/**
 * Every registered device belonging to a member of this list — the fan-out set
 * for a wake push (M2).
 *
 * This is not an authorization query and must never be used as one. It answers
 * "who should be told", after the Worker has already decided that the writer
 * was allowed to write.
 */
export function selectDevicesForList(sql: SqlStorage, listId: string): ListDeviceRow[] {
  const cursor = sql.exec(
    `SELECT d.device_id, d.user_id, d.fcm_token, m.notify_events
       FROM devices d
       JOIN memberships m ON m.user_id = d.user_id
      WHERE m.list_id = ?
      ORDER BY d.device_id`,
    listId,
  );
  return [...cursor].map((row) => ({
    deviceId: String(row.device_id),
    userId: String(row.user_id),
    fcmToken: String(row.fcm_token),
    notify: parseNotifyEvents(row.notify_events).length > 0,
  }));
}

export interface RateLimitRow {
  count: number;
  windowStartedAt: number;
}

export function selectRateLimit(sql: SqlStorage, bucket: string): RateLimitRow | null {
  const rows = [
    ...sql.exec("SELECT count, window_started_at FROM rate_limits WHERE bucket = ?", bucket),
  ];
  const row = rows[0];
  if (row === undefined) return null;
  return { count: Number(row.count), windowStartedAt: Number(row.window_started_at) };
}

export function upsertRateLimit(sql: SqlStorage, bucket: string, row: RateLimitRow): void {
  sql.exec(
    `INSERT INTO rate_limits (bucket, count, window_started_at) VALUES (?, ?, ?)
     ON CONFLICT (bucket) DO UPDATE SET count = excluded.count,
                                        window_started_at = excluded.window_started_at`,
    bucket,
    row.count,
    row.windowStartedAt,
  );
}

/** Windows that closed long ago say nothing. Pruned as we go (ADR 0004). */
export function deleteStaleRateLimits(sql: SqlStorage, before: number): void {
  sql.exec("DELETE FROM rate_limits WHERE window_started_at < ?", before);
}

export interface MagicLinkRow {
  tokenHash: string;
  email: string;
  expiresAt: string;
  createdAt: string;
  /** Opaque handle returned to the client for delivery-status polling
   * (`UsersRoom.magicLinkStatus`) — never the email itself. Null for a send
   * that predates 0006_magic_link_delivery. */
  requestId: string | null;
  /** Brevo's id for the send, looked up against its event-report API.
   * Null when Brevo accepted the send without handing one back. */
  messageId: string | null;
  /** Keyed hash of the code mailed with the link (ADR 0008). Null on a row
   * from before 0009_magic_link_codes, which cannot be redeemed by code. */
  codeHash: string | null;
  /** Wrong codes tried against this row so far. */
  codeAttempts: number;
}

export function insertMagicLink(sql: SqlStorage, row: MagicLinkRow): void {
  sql.exec(
    `INSERT INTO magic_links
       (token_hash, email, expires_at, created_at, request_id, message_id, code_hash, code_attempts)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
    row.tokenHash,
    row.email,
    row.expiresAt,
    row.createdAt,
    row.requestId,
    row.messageId,
    row.codeHash,
    row.codeAttempts,
  );
}

/** A new request for an email replaces its outstanding link rather than
 * letting two be valid at once (ADR 0005) — called before `insertMagicLink`. */
export function deleteMagicLinksForEmail(sql: SqlStorage, email: string): void {
  sql.exec("DELETE FROM magic_links WHERE email = ?", email);
}

export function selectMagicLink(sql: SqlStorage, tokenHash: string): MagicLinkRow | null {
  const rows = [
    ...sql.exec(`SELECT ${MAGIC_LINK_COLUMNS} FROM magic_links WHERE token_hash = ?`, tokenHash),
  ];
  return rowToMagicLink(rows[0]);
}

/** Looked up by `UsersRoom.magicLinkStatus` on each delivery-status poll. */
export function selectMagicLinkByRequestId(
  sql: SqlStorage,
  requestId: string,
): MagicLinkRow | null {
  const rows = [
    ...sql.exec(`SELECT ${MAGIC_LINK_COLUMNS} FROM magic_links WHERE request_id = ?`, requestId),
  ];
  return rowToMagicLink(rows[0]);
}

/** The outstanding link for one address — there is at most one, since a new
 * request replaces the old (ADR 0005). How a typed code finds its row. */
export function selectMagicLinkByEmail(sql: SqlStorage, email: string): MagicLinkRow | null {
  const rows = [
    ...sql.exec(`SELECT ${MAGIC_LINK_COLUMNS} FROM magic_links WHERE email = ?`, email),
  ];
  return rowToMagicLink(rows[0]);
}

/** One more wrong code against a link; answers the new count. */
export function incrementMagicCodeAttempts(sql: SqlStorage, tokenHash: string): number {
  const rows = [
    ...sql.exec(
      `UPDATE magic_links SET code_attempts = code_attempts + 1 WHERE token_hash = ?
       RETURNING code_attempts`,
      tokenHash,
    ),
  ];
  return Number(rows[0]?.code_attempts ?? 0);
}

const MAGIC_LINK_COLUMNS =
  "token_hash, email, expires_at, created_at, request_id, message_id, code_hash, code_attempts";

function rowToMagicLink(row: Record<string, SqlStorageValue> | undefined): MagicLinkRow | null {
  if (row === undefined) return null;
  return {
    tokenHash: String(row.token_hash),
    email: String(row.email),
    expiresAt: String(row.expires_at),
    createdAt: String(row.created_at),
    requestId: row.request_id === null ? null : String(row.request_id),
    messageId: row.message_id === null ? null : String(row.message_id),
    codeHash: row.code_hash === null || row.code_hash === undefined ? null : String(row.code_hash),
    codeAttempts: Number(row.code_attempts ?? 0),
  };
}

/** Burns the token — every path through `verifyMagicLink` deletes it,
 * matched or not (found-but-expired included), so the same link can never be
 * tapped twice (ADR 0005). */
export function deleteMagicLink(sql: SqlStorage, tokenHash: string): void {
  sql.exec("DELETE FROM magic_links WHERE token_hash = ?", tokenHash);
}

export function deleteExpiredMagicLinks(sql: SqlStorage, before: string): void {
  sql.exec("DELETE FROM magic_links WHERE expires_at < ?", before);
}

export interface AccountDeletionRequestRow {
  tokenHash: string;
  userId: string;
  expiresAt: string;
  createdAt: string;
}

/** A mailed "delete my account" link, by the hash of its token (ADR 0007). */
export function insertAccountDeletionRequest(
  sql: SqlStorage,
  row: AccountDeletionRequestRow,
): void {
  sql.exec(
    `INSERT INTO account_deletion_requests (token_hash, user_id, expires_at, created_at)
     VALUES (?, ?, ?, ?)`,
    row.tokenHash,
    row.userId,
    row.expiresAt,
    row.createdAt,
  );
}

export function selectAccountDeletionRequest(
  sql: SqlStorage,
  tokenHash: string,
): AccountDeletionRequestRow | null {
  const rows = [
    ...sql.exec(
      `SELECT token_hash, user_id, expires_at, created_at
         FROM account_deletion_requests WHERE token_hash = ?`,
      tokenHash,
    ),
  ];
  const row = rows[0];
  if (row === undefined) return null;
  return {
    tokenHash: String(row.token_hash),
    userId: String(row.user_id),
    expiresAt: String(row.expires_at),
    createdAt: String(row.created_at),
  };
}

export function deleteAccountDeletionRequest(sql: SqlStorage, tokenHash: string): void {
  sql.exec("DELETE FROM account_deletion_requests WHERE token_hash = ?", tokenHash);
}

/** One outstanding link per account: a new request replaces the old, and an
 * erased account leaves none behind. */
export function deleteAccountDeletionRequestsForUser(sql: SqlStorage, userId: string): void {
  sql.exec("DELETE FROM account_deletion_requests WHERE user_id = ?", userId);
}

export function deleteExpiredAccountDeletionRequests(sql: SqlStorage, before: string): void {
  sql.exec("DELETE FROM account_deletion_requests WHERE expires_at < ?", before);
}
