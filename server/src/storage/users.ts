/**
 * SQL against the UsersRoom DO's SQLite. No business rules here (D1) — it
 * reads and writes rows; it does not decide whether a password is right or
 * whether a token may be rotated.
 */
import type { Membership, MembershipRole } from "@dielys/protocol";
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

export function countUsers(sql: SqlStorage): number {
  const row = sql.exec("SELECT COUNT(*) AS n FROM users").one();
  return Number(row.n);
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
      "SELECT list_id, role, position FROM memberships WHERE user_id = ? AND list_id = ?",
      userId,
      listId,
    ),
  ];
  const row = rows[0];
  if (row === undefined) return null;
  return toMembership(row);
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
    `SELECT list_id, role, position FROM memberships
     WHERE user_id = ?
     ORDER BY COALESCE(position, '~'), created_at, list_id`,
    userId,
  );
  return [...cursor].map(toMembership);
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
  };
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

/**
 * Every registered device belonging to a member of this list — the fan-out set
 * for a wake push (M2).
 *
 * This is not an authorization query and must never be used as one. It answers
 * "who should be told", after the Worker has already decided that the writer
 * was allowed to write.
 */
export function selectDevicesForList(sql: SqlStorage, listId: string): DeviceRow[] {
  const cursor = sql.exec(
    `SELECT d.device_id, d.user_id, d.fcm_token
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
}

export function insertMagicLink(sql: SqlStorage, row: MagicLinkRow): void {
  sql.exec(
    `INSERT INTO magic_links (token_hash, email, expires_at, created_at, request_id, message_id)
     VALUES (?, ?, ?, ?, ?, ?)`,
    row.tokenHash,
    row.email,
    row.expiresAt,
    row.createdAt,
    row.requestId,
    row.messageId,
  );
}

/** A new request for an email replaces its outstanding link rather than
 * letting two be valid at once (ADR 0005) — called before `insertMagicLink`. */
export function deleteMagicLinksForEmail(sql: SqlStorage, email: string): void {
  sql.exec("DELETE FROM magic_links WHERE email = ?", email);
}

export function selectMagicLink(sql: SqlStorage, tokenHash: string): MagicLinkRow | null {
  const rows = [
    ...sql.exec(
      `SELECT token_hash, email, expires_at, created_at, request_id, message_id
       FROM magic_links WHERE token_hash = ?`,
      tokenHash,
    ),
  ];
  return rowToMagicLink(rows[0]);
}

/** Looked up by `UsersRoom.magicLinkStatus` on each delivery-status poll. */
export function selectMagicLinkByRequestId(sql: SqlStorage, requestId: string): MagicLinkRow | null {
  const rows = [
    ...sql.exec(
      `SELECT token_hash, email, expires_at, created_at, request_id, message_id
       FROM magic_links WHERE request_id = ?`,
      requestId,
    ),
  ];
  return rowToMagicLink(rows[0]);
}

function rowToMagicLink(row: Record<string, SqlStorageValue> | undefined): MagicLinkRow | null {
  if (row === undefined) return null;
  return {
    tokenHash: String(row.token_hash),
    email: String(row.email),
    expiresAt: String(row.expires_at),
    createdAt: String(row.created_at),
    requestId: row.request_id === null ? null : String(row.request_id),
    messageId: row.message_id === null ? null : String(row.message_id),
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
