import { DurableObject } from "cloudflare:workers";
import type { AuthErrorCode, ListMember, Membership, MembershipRole } from "@dielys/protocol";
import { INVITE_TOKEN_TTL_SECONDS, signInviteToken } from "../auth/jwt.js";
import {
  generateRefreshToken,
  hashPassword,
  hashRefreshToken,
  PASSWORD_ITERATIONS,
  verifyPassword,
} from "../auth/password.js";
import {
  bucketFor,
  emailKey,
  INVITE_EMAIL_PER_LIST,
  INVITE_EMAIL_PER_RECIPIENT,
  LOGIN_PER_CLIENT,
  MAGIC_REQUEST_PER_CLIENT,
  MAGIC_REQUEST_PER_EMAIL,
  MAGIC_STATUS_PER_CLIENT,
  MAGIC_VERIFY_PER_CLIENT,
  type RateLimit,
  REGISTER_GLOBAL,
  REGISTER_PER_CLIENT,
} from "../auth/ratelimit.js";
import {
  type EmailSender,
  isEmailDelivered,
  sendInviteEmail as mailInvite,
  parseEmailSender,
  sendMagicLinkEmail,
} from "../email/brevo.js";
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
  deleteExpiredMagicLinks,
  deleteExpiredRefreshTokens,
  deleteMagicLink,
  deleteMagicLinksForEmail,
  deleteMembership,
  deleteRefreshTokensForUser,
  deleteStaleRateLimits,
  insertMagicLink,
  insertMembership,
  insertRefreshToken,
  insertUser,
  markRefreshTokenUsed,
  selectDevicesForList,
  selectListMembers,
  selectMagicLink,
  selectMagicLinkByRequestId,
  selectMembership,
  selectMemberships,
  selectRateLimit,
  selectRefreshToken,
  selectUserByEmail,
  selectUserById,
  updateMembershipPosition,
  updateUserPassword,
  upsertDevice,
  upsertRateLimit,
} from "../storage/users.js";

/** 30 days (L1). */
export const REFRESH_TOKEN_TTL_MS = 30 * 24 * 60 * 60 * 1000;

/** 15 minutes (ADR 0005). Long enough to switch to a mail app and find the
 * message, short enough that a link sitting unread in an inbox stops being
 * useful quickly. */
export const MAGIC_LINK_TTL_MS = 15 * 60 * 1000;

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
  /** Same lazy-resolve shape as [fcm] — see its comment. Null on a deployment
   * with no EMAIL_FROM configured, which is what makes /auth/magic/* fail
   * closed (ADR 0005): the Worker checks this before ever calling
   * [requestMagicLink]. */
  private emailSender: EmailSender | null = null;
  private emailSenderResolved = false;

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

  // --- Passwordless magic-link sign-in (ADR 0005) ---------------------------

  /**
   * Mints a token, mails a link carrying it, and stores only the token's
   * hash. Answered the same way whether or not an account exists for this
   * email — unlike registration (ADR 0004), there is nothing to leak:
   * [verifyMagicLink] creates the account on first use, so "link sent" is
   * honest for every address either way.
   *
   * Both limiters are consumed before Brevo is called, mirroring [register]:
   * the expensive/external step never runs for a request already over budget.
   */
  async requestMagicLink(
    email: string,
    clientKey: string,
    now: number,
  ): Promise<UsersResult<{ expiresIn: number; requestId: string }>> {
    const normalized = normalizeEmail(email);

    if (!this.consume(MAGIC_REQUEST_PER_CLIENT, clientKey, now)) {
      return { ok: false, code: "rate-limited" };
    }
    const emailBucket = await emailKey(normalized, this.env.JWT_SIGNING_KEY);
    if (!this.consume(MAGIC_REQUEST_PER_EMAIL, emailBucket, now)) {
      // Same code as the per-client limit: the caller cannot tell whether it
      // was their own address or this email's own limit that refused them.
      return { ok: false, code: "rate-limited" };
    }

    const sender = this.email();
    if (sender === null) {
      log("error", "usersroom.magiclink.unconfigured", {});
      return { ok: false, code: "internal" };
    }

    const token = generateRefreshToken();
    const link = magicLinkUrl(this.env.APP_BASE_URL, token);
    const result = await sendMagicLinkEmail(
      this.env.BREVO_API_KEY,
      sender,
      normalized,
      link,
      MAGIC_LINK_TTL_MS / 60_000,
    );
    if (!result.sent) return { ok: false, code: "internal" };

    // Its own random value, not derived from the token: leaking it (it goes
    // straight to the client, unlike the token which only ever leaves this
    // Worker inside a mailed link) must not help anyone guess or verify the
    // sign-in token itself (ADR 0005's reasoning for the token, reapplied).
    const requestId = generateRefreshToken();

    // Stored only after the send succeeds: a token nobody's inbox will ever
    // show is not worth spending a write on, and it would just sit there
    // until deleteExpiredMagicLinks caught up with it.
    const tokenHash = await hashRefreshToken(token);
    this.ctx.storage.transactionSync(() => {
      deleteMagicLinksForEmail(this.sql, normalized);
      insertMagicLink(this.sql, {
        tokenHash,
        email: normalized,
        expiresAt: new Date(now + MAGIC_LINK_TTL_MS).toISOString(),
        createdAt: new Date(now).toISOString(),
        requestId,
        messageId: result.messageId,
      });
      deleteExpiredMagicLinks(this.sql, new Date(now).toISOString());
    });
    log("info", "usersroom.magiclink.sent", {});
    return { ok: true, value: { expiresIn: MAGIC_LINK_TTL_MS / 1000, requestId } };
  }

  /**
   * Polled by the client every few seconds while it waits (`GET
   * /auth/magic/status`) — never authenticated, since there is no session
   * yet, so `requestId` is the only thing that gates it (D4: no email in
   * either direction). An unknown or superseded request answers "not
   * delivered" rather than an error — the same enumeration reasoning
   * `invalid-token` uses, so a guessed or stale id learns nothing.
   *
   * Rate-limited like every other unauthenticated auth endpoint, but the
   * limit itself is the whole answer on refusal: skip the Brevo call and
   * say "not delivered yet" rather than surface a `rate-limited` the client
   * would have to do something with mid-poll.
   */
  async magicLinkStatus(
    requestId: string,
    clientKey: string,
    now: number,
  ): Promise<{ delivered: boolean }> {
    if (!this.consume(MAGIC_STATUS_PER_CLIENT, clientKey, now)) return { delivered: false };

    const row = selectMagicLinkByRequestId(this.sql, requestId);
    if (row === null || row.messageId === null) return { delivered: false };
    return { delivered: await isEmailDelivered(this.env.BREVO_API_KEY, row.messageId) };
  }

  // --- Invite emails (L3) ----------------------------------------------------

  /**
   * Mints an invite scoped to `recipientEmail` and mails the link directly —
   * the owner's own device never sees the bearer token, only whether the
   * send worked. Nothing is stored: unlike a magic link there is no status
   * to poll later, so a token nobody's inbox ever shows costs this DO
   * nothing to have minted.
   *
   * Both rate limits are consumed before Brevo is called, mirroring
   * [requestMagicLink]: an over-budget caller never costs an external API
   * call.
   */
  async sendInviteEmail(
    inviterId: string,
    listId: string,
    listTitle: string,
    recipientEmail: string,
    now: number,
  ): Promise<UsersResult<{ expiresIn: number }>> {
    const normalized = normalizeEmail(recipientEmail);

    if (!this.consume(INVITE_EMAIL_PER_LIST, listId, now)) {
      return { ok: false, code: "rate-limited" };
    }
    const emailBucket = await emailKey(normalized, this.env.JWT_SIGNING_KEY);
    if (!this.consume(INVITE_EMAIL_PER_RECIPIENT, emailBucket, now)) {
      // Same code as the per-list limit: the caller cannot tell whether it
      // was this list or this recipient's own limit that refused them.
      return { ok: false, code: "rate-limited" };
    }

    const sender = this.email();
    if (sender === null) {
      log("error", "usersroom.invite.unconfigured", {});
      return { ok: false, code: "internal" };
    }

    const token = await signInviteToken(
      { listId, sub: inviterId, email: normalized },
      this.env.JWT_SIGNING_KEY,
      now,
    );
    const link = inviteLinkUrl(this.env.APP_BASE_URL, token);
    const ttlDays = INVITE_TOKEN_TTL_SECONDS / (24 * 60 * 60);
    const result = await mailInvite(
      this.env.BREVO_API_KEY,
      sender,
      normalized,
      link,
      listTitle,
      ttlDays,
    );
    if (!result.sent) return { ok: false, code: "internal" };

    log("info", "usersroom.invite.sent", { listId });
    return { ok: true, value: { expiresIn: INVITE_TOKEN_TTL_SECONDS } };
  }

  /**
   * Verifies a token and signs in, creating the account first if the email it
   * names has never been seen (ADR 0005) — the same collapse-into-one-call
   * shape as [register]. The created account gets a password hash nobody
   * knows (random bytes, never derived from anything) rather than no
   * password at all: the `users` table's password columns are `NOT NULL`,
   * and a hash nobody can reproduce is what makes `/auth/login` correctly
   * refuse it forever, not a schema change away from also making that column
   * nullable.
   *
   * The token is burned on every path through here, matched or not — a
   * link is single-use whether it was tapped correctly, twice, or after it
   * expired.
   */
  async verifyMagicLink(
    token: string,
    deviceId: string,
    clientKey: string,
    now: number,
  ): Promise<UsersResult<Session>> {
    if (!this.consume(MAGIC_VERIFY_PER_CLIENT, clientKey, now)) {
      return { ok: false, code: "rate-limited" };
    }

    const tokenHash = await hashRefreshToken(token);
    const row = selectMagicLink(this.sql, tokenHash);
    if (row === null) return { ok: false, code: "invalid-token" };

    this.ctx.storage.transactionSync(() => deleteMagicLink(this.sql, tokenHash));

    if (Date.parse(row.expiresAt) <= now) {
      return { ok: false, code: "token-expired" };
    }

    let user = selectUserByEmail(this.sql, row.email);
    if (user === null) {
      const created = await this.createPasswordlessUser(row.email, now);
      if (!created.ok) return created;
      user = selectUserByEmail(this.sql, row.email);
    }
    // Unreachable except by a storage bug: just written or just read above.
    if (user === null) return { ok: false, code: "not-found" };

    return { ok: true, value: await this.issueSession(user.id, deviceId, now) };
  }

  /** See the note on [verifyMagicLink] for why the password is random rather
   * than absent. Not rate-limited itself — [verifyMagicLink] already consumed
   * the caller's budget before reaching here. */
  private async createPasswordlessUser(email: string, now: number): Promise<UsersResult<string>> {
    if (selectUserByEmail(this.sql, email) !== null) {
      return { ok: false, code: "already-exists" };
    }
    const unusable = await hashPassword(generateRefreshToken());
    const id = crypto.randomUUID();
    this.ctx.storage.transactionSync(() => {
      insertUser(this.sql, {
        id,
        email,
        password: unusable,
        createdAt: new Date(now).toISOString(),
      });
    });
    log("info", "usersroom.user.created", { userId: id, via: "magic-link" });
    return { ok: true, value: id };
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

  /** Who is on one list (#60). The route has already established that the
   *  caller is one of them, so there is no check to repeat here. */
  async listMembers(listId: string): Promise<ListMember[]> {
    return selectListMembers(this.sql, listId);
  }

  /**
   * Takes [targetUserId] off [listId] (#60).
   *
   * Two callers may (L3): the owner removing somebody else, or anybody
   * removing themselves. An owner removing *themselves* is refused — a list
   * with no owner is one nobody can ever share again, and deleting the list is
   * the thing they actually mean.
   *
   * `forbidden` for a membership that is not there, not `not-found`: the same
   * rule [setListPosition] follows, so this cannot be used to ask who is on a
   * list.
   */
  async removeMembership(
    callerUserId: string,
    listId: string,
    targetUserId: string,
  ): Promise<UsersResult<{ listId: string; userId: string }>> {
    const caller = selectMembership(this.sql, callerUserId, listId);
    if (caller === null) return { ok: false, code: "forbidden" };

    const removingSelf = callerUserId === targetUserId;
    if (!removingSelf && caller.role !== "owner") {
      log("info", "usersroom.membership.remove-denied", { listId });
      return { ok: false, code: "forbidden" };
    }
    if (removingSelf && caller.role === "owner") {
      log("info", "usersroom.membership.owner-cannot-leave", { listId });
      return { ok: false, code: "forbidden" };
    }

    let removed = false;
    this.ctx.storage.transactionSync(() => {
      removed = deleteMembership(this.sql, targetUserId, listId);
    });
    if (!removed) return { ok: false, code: "forbidden" };

    log("info", "usersroom.membership.removed", { listId, removingSelf });
    return { ok: true, value: { listId, userId: targetUserId } };
  }

  /**
   * Moves one list in this user's own ordering (PROTOCOL.md "Ordering the
   * lists"). Nobody else on the list sees it: the key lives on the membership,
   * not on the list.
   *
   * Last-write-wins on one column the caller alone owns, so a replayed request
   * is the same state and no idempotency key is needed. Refused with
   * `forbidden` rather than `not-found` when the caller is not a member (L3).
   */
  async setListPosition(
    userId: string,
    listId: string,
    position: string,
  ): Promise<UsersResult<{ listId: string; position: string }>> {
    let moved = false;
    this.ctx.storage.transactionSync(() => {
      moved = updateMembershipPosition(this.sql, userId, listId, position);
    });
    if (!moved) {
      log("warn", "usersroom.position.not-a-member", { userId, listId });
      return { ok: false, code: "forbidden" };
    }
    return { ok: true, value: { listId, position } };
  }

  /**
   * Idempotent by design (L3): accepting an invite twice is a no-op rather
   * than an error, matching the spirit of F5.2.
   *
   * `expectedEmail`, when given, is the invite's own bound recipient (its
   * JWT `email` claim) — this is what makes holding the token insufficient
   * to join: the accepting account's own email must match it too. `forbidden`
   * is the same code an owner-only route already answers with, so a caller
   * cannot use this response to distinguish "wrong account" from any other
   * kind of "you may not do this."
   */
  async addMembership(
    userId: string,
    listId: string,
    role: MembershipRole,
    now: number,
    expectedEmail?: string,
  ): Promise<UsersResult<{ alreadyMember: boolean }>> {
    const user = selectUserById(this.sql, userId);
    if (user === null) return { ok: false, code: "not-found" };
    if (expectedEmail !== undefined && normalizeEmail(user.email) !== expectedEmail) {
      log("info", "usersroom.membership.wrong-recipient", { listId });
      return { ok: false, code: "forbidden" };
    }

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

  /** Built once per instance, mirroring [fcm]. Unlike FCM, an unconfigured
   * sender is logged as an error, not an info: FCM absent just means slower
   * sync, EMAIL_FROM absent means every /auth/magic/request fails. */
  private email(): EmailSender | null {
    if (!this.emailSenderResolved) {
      this.emailSenderResolved = true;
      this.emailSender = parseEmailSender(this.env.EMAIL_FROM, this.env.EMAIL_FROM_NAME);
    }
    return this.emailSender;
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
    // Dev deployment only (env.ENVIRONMENT, wrangler.jsonc) — prod stays limited.
    if (this.env.ENVIRONMENT === "dev") return true;

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

/**
 * The link an OTP email used to carry a code; this carries a token instead,
 * under the HTTPS App Link domain the Android manifest verifies against
 * (ADR 0005) — `https://dielys.com/magic?token=…`, opened by the app itself
 * rather than a browser once `assetlinks.json` is in place.
 */
function magicLinkUrl(baseUrl: string, token: string): string {
  const url = new URL("/magic", baseUrl);
  url.searchParams.set("token", token);
  return url.toString();
}

/**
 * Same App Link shape as [magicLinkUrl], for an invite instead of a sign-in.
 * The query param is `t`, not `token` — matching `InviteLink.PREFIX` on the
 * Android side, which is what actually has to parse this back out.
 */
function inviteLinkUrl(baseUrl: string, token: string): string {
  const url = new URL("/invite", baseUrl);
  url.searchParams.set("t", token);
  return url.toString();
}
