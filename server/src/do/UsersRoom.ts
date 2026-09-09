import { DurableObject } from "cloudflare:workers";
import type { AuthErrorCode, Membership, MembershipRole } from "@dielys/protocol";
import {
  generateRefreshToken,
  hashPassword,
  hashRefreshToken,
  PASSWORD_ITERATIONS,
  verifyPassword,
} from "../auth/password.js";
import {
  bucketFor,
  LOGIN_PER_CLIENT,
  type RateLimit,
  REGISTER_GLOBAL,
  REGISTER_PER_CLIENT,
} from "../auth/ratelimit.js";
import { log } from "../lib/log.js";
import {
  FcmSender,
  parseServiceAccount,
  type ServiceAccount,
  type WakeTarget,
  wakeData,
} from "../push/fcm.js";
import { applyPendingMigrations, USERS_MIGRATIONS } from "../storage/migrations.js";
import {
  countListMembers,
  countUsers,
  type DeviceRow,
  deleteDevice,
  deleteExpiredRefreshTokens,
  deleteRefreshTokensForUser,
  deleteStaleRateLimits,
  insertMembership,
  insertRefreshToken,
  insertUser,
  markRefreshTokenUsed,
  selectDevicesForList,
  selectMembership,
  selectMemberships,
  selectRateLimit,
  selectRefreshToken,
  selectUserByEmail,
  selectUserById,
  updateUserPassword,
  upsertDevice,
  upsertRateLimit,
} from "../storage/users.js";

/** 30 days (L1). */
export const REFRESH_TOKEN_TTL_MS = 30 * 24 * 60 * 60 * 1000;

/**
 * A ceiling on one fan-out, not a household size. Two people have two phones;
 * a number this far above that means something is wrong — a runaway
 * registration loop, say — and an unbounded loop of outbound `fetch` calls is
 * not the place to find out.
 */
const MAX_WAKE_TARGETS = 32;

/**
 * A window that closed this long ago cannot affect any limit, so its row is
 * dead weight. The longest window in use, by construction — a shorter value
 * here would silently reset the daily registration ceiling.
 */
const RATE_LIMIT_RETENTION_MS = REGISTER_GLOBAL.windowMs;

export type UsersResult<T> = { ok: true; value: T } | { ok: false; code: AuthErrorCode };

export interface Session {
  userId: string;
  refreshToken: string;
}

/**
 * Singleton Durable Object (idFromName("users-v1")) holding accounts,
 * device-scoped refresh tokens, and list membership. See
 * docs/adr/0002-authentication.md and CODE_STANDARD.md L1-L3, M2.
 *
 * It is a singleton by construction — one fixed name — rather than one per
 * entity, which is why it does not conflict with D3's one-DO-per-list rule.
 *
 * `ListRoom` never checks membership itself; the Worker checks it here before
 * forwarding any request to a `ListRoom`. Authorization lives in one place.
 *
 * The methods below are the DO's RPC surface. Password hashing happens here
 * rather than in the Worker because this object owns the `users` table and
 * `auth/jwt.ts` is barred from storage access (D1); it also puts the one
 * CPU-expensive operation in the object with the larger CPU budget.
 */
export class UsersRoom extends DurableObject {
  private readonly sql: SqlStorage;
  /**
   * Instance state, never module-level (D3). Null until the first push, and
   * null forever on a deployment with no FCM credential — where the app still
   * syncs, just no faster than the half-hourly worker (H3.12).
   */
  private sender: FcmSender | null = null;
  private senderResolved = false;

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    this.sql = ctx.storage.sql;
    ctx.blockConcurrencyWhile(async () => {
      ctx.storage.transactionSync(() => {
        applyPendingMigrations(this.sql, USERS_MIGRATIONS);
      });
    });
  }

  /**
   * Creates the account and nothing else. Reached by the `ADMIN_TOKEN` route,
   * which is how the first account on a fresh deployment is made, and by
   * [register], which is the public path (L2, ADR 0004).
   *
   * Rate limiting is the caller's job, not this method's: the admin route is
   * already behind a secret, and limiting it would mean a held secret could
   * lock itself out.
   */
  async createUser(email: string, password: string, now: number): Promise<UsersResult<string>> {
    const normalized = normalizeEmail(email);
    if (selectUserByEmail(this.sql, normalized) !== null) {
      return { ok: false, code: "already-exists" };
    }

    const hashed = await hashPassword(password);
    const id = crypto.randomUUID();
    this.ctx.storage.transactionSync(() => {
      insertUser(this.sql, {
        id,
        email: normalized,
        password: hashed,
        createdAt: new Date(now).toISOString(),
      });
    });
    // Never log the email — that is user content (D4). The id is enough to
    // correlate with anything else.
    log("info", "usersroom.user.created", { userId: id });
    return { ok: true, value: id };
  }

  /**
   * Public registration (L2, ADR 0004). Creates the account and signs the
   * caller in, because a second round trip to log in afterwards would be two
   * chances to fail for one intent.
   *
   * Both limits are consumed before the password is hashed. PBKDF2 is the
   * expensive thing on this path, and a limiter that runs after it has already
   * paid for the request it was meant to refuse.
   *
   * `already-exists` here is an account-enumeration oracle. It is accepted, and
   * bounded by [REGISTER_PER_CLIENT] — see ADR 0004. Login must not leak the
   * same thing, and does not.
   */
  async register(
    email: string,
    password: string,
    deviceId: string,
    clientKey: string,
    now: number,
  ): Promise<UsersResult<Session>> {
    if (!this.consume(REGISTER_PER_CLIENT, clientKey, now)) {
      return { ok: false, code: "rate-limited" };
    }
    if (!this.consume(REGISTER_GLOBAL, "all", now)) {
      log("warn", "usersroom.register.ceiling", {});
      return { ok: false, code: "rate-limited" };
    }

    const created = await this.createUser(email, password, now);
    if (!created.ok) return created;

    return { ok: true, value: await this.issueSession(created.value, deviceId, now) };
  }

  async login(
    email: string,
    password: string,
    deviceId: string,
    clientKey: string,
    now: number,
  ): Promise<UsersResult<Session>> {
    // Before the hash, and before the lookup: a limiter that runs afterwards
    // has already spent the CPU it exists to protect.
    if (!this.consume(LOGIN_PER_CLIENT, clientKey, now)) {
      return { ok: false, code: "rate-limited" };
    }

    const user = selectUserByEmail(this.sql, normalizeEmail(email));

    if (user === null) {
      // Hash anyway. Returning early here would make "no such account" measurably
      // faster than "wrong password", turning the one shared error code into a
      // timing oracle for which emails exist.
      await hashPassword(password);
      return { ok: false, code: "invalid-credentials" };
    }

    if (!(await verifyPassword(password, user.password))) {
      return { ok: false, code: "invalid-credentials" };
    }

    // Re-hash at the current cost when the stored one is behind. This is what
    // makes PASSWORD_ITERATIONS raisable later without locking anyone out.
    if (user.password.iterations < PASSWORD_ITERATIONS) {
      const upgraded = await hashPassword(password);
      this.ctx.storage.transactionSync(() => updateUserPassword(this.sql, user.id, upgraded));
      log("info", "usersroom.password.rehashed", {
        userId: user.id,
        from: user.password.iterations,
        to: upgraded.iterations,
      });
    }

    return { ok: true, value: await this.issueSession(user.id, deviceId, now) };
  }

  /**
   * Rotation (L1): the presented token is spent and a new one returned. The
   * spent row is kept, not deleted — that is what makes a replay detectable
   * rather than indistinguishable from a token that never existed.
   */
  async rotateRefreshToken(
    refreshToken: string,
    deviceId: string,
    now: number,
  ): Promise<UsersResult<Session>> {
    const tokenHash = await hashRefreshToken(refreshToken);
    const row = selectRefreshToken(this.sql, tokenHash);

    if (row === null) return { ok: false, code: "invalid-credentials" };

    if (row.usedAt !== null) {
      // Someone is presenting a token that was already exchanged. Either it
      // was stolen, or a legitimate client replayed one — both mean the
      // token is loose, so every session for this user goes (L1).
      const revoked = this.ctx.storage.transactionSync(() =>
        deleteRefreshTokensForUser(this.sql, row.userId),
      );
      log("warn", "usersroom.refresh.reuse-detected", { userId: row.userId, revoked });
      return { ok: false, code: "token-reused" };
    }

    if (Date.parse(row.expiresAt) <= now) {
      return { ok: false, code: "token-expired" };
    }

    if (row.deviceId !== deviceId) {
      // Device-scoped (L1). Not treated as a reuse signal: a client bug that
      // sent the wrong device id should not log the household out.
      log("warn", "usersroom.refresh.device-mismatch", { userId: row.userId });
      return { ok: false, code: "invalid-credentials" };
    }

    const nowIso = new Date(now).toISOString();
    const token = generateRefreshToken();
    const newHash = await hashRefreshToken(token);

    this.ctx.storage.transactionSync(() => {
      markRefreshTokenUsed(this.sql, tokenHash, nowIso);
      insertRefreshToken(this.sql, {
        tokenHash: newHash,
        userId: row.userId,
        deviceId,
        issuedAt: nowIso,
        expiresAt: new Date(now + REFRESH_TOKEN_TTL_MS).toISOString(),
        usedAt: null,
      });
      deleteExpiredRefreshTokens(this.sql, nowIso);
    });

    return { ok: true, value: { userId: row.userId, refreshToken: token } };
  }

  async checkMembership(userId: string, listId: string): Promise<Membership | null> {
    return selectMembership(this.sql, userId, listId);
  }

  async listMemberships(userId: string): Promise<Membership[]> {
    return selectMemberships(this.sql, userId);
  }

  /**
   * Idempotent by design (L3): accepting an invite twice is a no-op rather
   * than an error, matching the spirit of F5.2.
   */
  async addMembership(
    userId: string,
    listId: string,
    role: MembershipRole,
    now: number,
  ): Promise<UsersResult<{ alreadyMember: boolean }>> {
    if (selectUserById(this.sql, userId) === null) return { ok: false, code: "not-found" };

    const existing = selectMembership(this.sql, userId, listId);
    if (existing !== null) return { ok: true, value: { alreadyMember: true } };

    this.ctx.storage.transactionSync(() => {
      insertMembership(this.sql, userId, listId, role, new Date(now).toISOString());
    });
    log("info", "usersroom.membership.added", { userId, listId, role });
    return { ok: true, value: { alreadyMember: false } };
  }

  /**
   * Claims an unowned list id as owner.
   *
   * List ids are client-generated (F5.1), so the server never mints one and
   * cannot hand out ownership at creation time. Instead the first caller to
   * claim an id that nobody holds becomes its owner; a claim on an id someone
   * else already holds is refused. Re-claiming a list you are already on is a
   * no-op, so an outbox retry of the same create is harmless.
   *
   * A UUIDv7 is not guessable in practice, so this is not a land-grab risk;
   * the check exists so that a collision or a malicious guess fails loudly
   * rather than silently joining someone else's shopping list.
   */
  async claimList(
    userId: string,
    listId: string,
    now: number,
  ): Promise<UsersResult<{ alreadyMember: boolean }>> {
    const existing = selectMembership(this.sql, userId, listId);
    if (existing !== null) return { ok: true, value: { alreadyMember: true } };

    if (countListMembers(this.sql, listId) > 0) {
      log("warn", "usersroom.claim.already-owned", { userId, listId });
      return { ok: false, code: "forbidden" };
    }

    this.ctx.storage.transactionSync(() => {
      insertMembership(this.sql, userId, listId, "owner", new Date(now).toISOString());
    });
    log("info", "usersroom.list.claimed", { userId, listId });
    return { ok: true, value: { alreadyMember: false } };
  }

  // --- Push (M2) ----------------------------------------------------------

  /**
   * Files this device's FCM token (M2). Called on every sync where the token
   * the client holds is not the one the server was last told, so it covers
   * both `onNewToken` and a first install.
   *
   * The device id comes from the caller's access token, never from a request
   * body — see the route in index.ts. Signing in as somebody else on the same
   * phone re-points the row rather than adding a second one, which is what
   * stops a handed-on phone being woken for its previous owner.
   */
  async registerDevice(
    userId: string,
    deviceId: string,
    fcmToken: string,
    now: number,
  ): Promise<void> {
    this.ctx.storage.transactionSync(() => {
      upsertDevice(this.sql, { deviceId, userId, fcmToken }, new Date(now).toISOString());
    });
    // The token is the address of somebody's phone. Log that one exists, never
    // what it is (D4).
    log("info", "usersroom.device.registered", { userId, deviceId });
  }

  /** Every registered device on this list. Not an authorization check — see
   * `selectDevicesForList`. Exposed so the fan-out can be tested without a
   * network. */
  async devicesForList(listId: string): Promise<DeviceRow[]> {
    return selectDevicesForList(this.sql, listId);
  }

  /**
   * Wakes the member devices that did not already get this change over a
   * socket (M2).
   *
   * `ListRoom` calls this after it has committed, passing the device ids it
   * knows are connected plus the one that made the write. Membership lives
   * here, so the fan-out lives here too — and the object that owns the device
   * rows is also the one that can drop a dead token without a second hop.
   *
   * Fire-and-forget by design: this returns as soon as the send is scheduled.
   * A push is a hint, and a caller that waited for Google before answering the
   * client would have made a write slower in order to make it no more correct.
   */
  async notifyListMembers(listId: string, seq: number, connected: string[]): Promise<void> {
    const sender = this.fcm();
    if (sender === null) return;

    const already = new Set(connected);
    const targets: WakeTarget[] = selectDevicesForList(this.sql, listId)
      .filter((device) => !already.has(device.deviceId))
      .slice(0, MAX_WAKE_TARGETS)
      .map((device) => ({ deviceId: device.deviceId, fcmToken: device.fcmToken }));

    // Logged on both sides of the decision, because a successful send says
    // nothing on its own: without this, "no wake in the log" cannot be told
    // apart from "every member device was already connected", and the two have
    // opposite meanings when a phone did not hear about a change.
    if (targets.length === 0) {
      log("info", "usersroom.wake.skipped", { listId, seq, connected: already.size });
      return;
    }

    log("info", "usersroom.wake.sent", { listId, seq, count: targets.length });

    this.ctx.waitUntil(
      sender.wake(targets, wakeData(listId, seq), Date.now()).then((gone) => {
        // An uninstalled app keeps its row otherwise, and every later write
        // pays a round trip to be told the same thing again.
        if (gone.length === 0) return;
        this.ctx.storage.transactionSync(() => {
          for (const deviceId of gone) deleteDevice(this.sql, deviceId);
        });
        log("info", "usersroom.device.dropped", { count: gone.length });
      }),
    );
  }

  /** Built once per instance, including the decision that there is nothing to
   * build. Parsing a service account costs nothing, but doing it per push
   * would hide how often it fails. */
  private fcm(): FcmSender | null {
    if (!this.senderResolved) {
      this.senderResolved = true;
      const account: ServiceAccount | null = parseServiceAccount(this.env.FCM_SERVICE_ACCOUNT_JSON);
      if (account === null) {
        log("info", "usersroom.push.disabled", {});
      } else {
        this.sender = new FcmSender(account);
      }
    }
    return this.sender;
  }

  /** Whether any account exists at all — used by the admin route to refuse
   * bootstrapping a second time without an explicit token. */
  async userCount(): Promise<number> {
    return countUsers(this.sql);
  }

  /**
   * Fixed window: the first attempt opens one, and everything inside it counts
   * against the same allowance until it closes. Returns whether this attempt is
   * within the limit.
   *
   * An attempt over the limit still increments, so hammering keeps the window
   * shut rather than rolling it — which is the point of refusing.
   */
  private consume(limit: RateLimit, clientKey: string, now: number): boolean {
    const bucket = bucketFor(limit, clientKey);
    return this.ctx.storage.transactionSync(() => {
      deleteStaleRateLimits(this.sql, now - RATE_LIMIT_RETENTION_MS);

      const current = selectRateLimit(this.sql, bucket);
      const open = current !== null && now - current.windowStartedAt < limit.windowMs;
      const next = open
        ? { count: current.count + 1, windowStartedAt: current.windowStartedAt }
        : { count: 1, windowStartedAt: now };

      upsertRateLimit(this.sql, bucket, next);
      return next.count <= limit.limit;
    });
  }

  private async issueSession(userId: string, deviceId: string, now: number): Promise<Session> {
    const token = generateRefreshToken();
    const tokenHash = await hashRefreshToken(token);
    const nowIso = new Date(now).toISOString();

    this.ctx.storage.transactionSync(() => {
      insertRefreshToken(this.sql, {
        tokenHash,
        userId,
        deviceId,
        issuedAt: nowIso,
        expiresAt: new Date(now + REFRESH_TOKEN_TTL_MS).toISOString(),
        usedAt: null,
      });
      deleteExpiredRefreshTokens(this.sql, nowIso);
    });

    return { userId, refreshToken: token };
  }
}

/**
 * Case-insensitive, trimmed. Two accounts differing only in the case of their
 * email would be two accounts to their owners' surprise, and the UNIQUE index
 * would not stop it.
 */
function normalizeEmail(email: string): string {
  return email.trim().toLowerCase();
}
