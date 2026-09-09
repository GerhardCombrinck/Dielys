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
      "SELECT list_id, role FROM memberships WHERE user_id = ? AND list_id = ?",
      userId,
      listId,
    ),
  ];
  const row = rows[0];
  if (row === undefined) return null;
  return { listId: String(row.list_id), role: String(row.role) as MembershipRole };
}

/** How many people are on a list at all. Zero means it is unclaimed. */
export function countListMembers(sql: SqlStorage, listId: string): number {
  const row = sql.exec("SELECT COUNT(*) AS n FROM memberships WHERE list_id = ?", listId).one();
  return Number(row.n);
}

export function selectMemberships(sql: SqlStorage, userId: string): Membership[] {
  const cursor = sql.exec(
    "SELECT list_id, role FROM memberships WHERE user_id = ? ORDER BY created_at",
    userId,
  );
  return [...cursor].map((row) => ({
    listId: String(row.list_id),
    // Written by insertMembership from a MembershipRole; SQLite has no enum.
    role: String(row.role) as MembershipRole,
  }));
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
