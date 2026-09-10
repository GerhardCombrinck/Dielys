/**
 * Validation at the boundary (F3). Pure — no storage, no env, no clock (D1).
 *
 * A parsed JSON result is `unknown`, not a typed message. Everything below
 * turns `unknown` into a protocol type or into a reason it is not one; nothing
 * anywhere else in the server is allowed to read a field off inbound data
 * without coming through here first.
 *
 * Hand-rolled rather than schema-library-driven: `protocol/` must have zero
 * runtime dependencies (F1), and the shapes are small enough that a dependency
 * would cost more than it saves (N1).
 */
import {
  type AcceptInviteRequest,
  type CatchUpRequest,
  type ClientHello,
  type CreateInviteRequest,
  type ListPatch,
  type LoginRequest,
  MAX_EMAIL_LENGTH,
  MAX_FCM_TOKEN_LENGTH,
  MAX_ID_LENGTH,
  MAX_PASSWORD_LENGTH,
  MAX_POSITION_LENGTH,
  MAX_TITLE_LENGTH,
  MAX_URL_LENGTH,
  MIN_PASSWORD_LENGTH,
  type Mutation,
  type RefreshRequest,
  type RegisterDeviceRequest,
  type RegisterRequest,
  type RequestMagicLinkRequest,
  type SetListPositionRequest,
  type TaskPatch,
  type VerifyMagicLinkRequest,
} from "@dielys/protocol";

export type Validated<T> = { ok: true; value: T } | { ok: false; reason: string };

function fail(reason: string): { ok: false; reason: string } {
  return { ok: false, reason };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isId(value: unknown): value is string {
  return typeof value === "string" && value.length > 0 && value.length <= MAX_ID_LENGTH;
}

/** Rejects NaN, Infinity and fractional seqs, all of which JSON allows. */
function isSeq(value: unknown): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0;
}

function isIsoTimestamp(value: unknown): value is string {
  if (typeof value !== "string") return false;
  const parsed = Date.parse(value);
  if (Number.isNaN(parsed)) return false;
  // Round-trip, so "2026-13-45" and other strings Date.parse is lenient about
  // cannot enter storage and come back out as something else.
  return new Date(parsed).toISOString() === value;
}

export function parseJson(raw: string): Validated<unknown> {
  try {
    return { ok: true, value: JSON.parse(raw) as unknown };
  } catch {
    return fail("not json");
  }
}

export function validateClientHello(input: unknown): Validated<ClientHello> {
  if (!isRecord(input)) return fail("not an object");
  if (input.type !== "hello") return fail("type is not hello");
  if (!isSeq(input.protocolVersion)) return fail("protocolVersion");
  if (!isId(input.listId)) return fail("listId");
  if (!isId(input.deviceId)) return fail("deviceId");
  if (!isSeq(input.cursor)) return fail("cursor");
  return {
    ok: true,
    value: {
      type: "hello",
      protocolVersion: input.protocolVersion,
      listId: input.listId,
      cursor: input.cursor,
      deviceId: input.deviceId,
    },
  };
}

export function validateCatchUpRequest(input: unknown): Validated<CatchUpRequest> {
  if (!isRecord(input)) return fail("not an object");
  if (input.type !== "catch-up") return fail("type is not catch-up");
  if (!isId(input.listId)) return fail("listId");
  if (!isSeq(input.since)) return fail("since");
  return { ok: true, value: { type: "catch-up", listId: input.listId, since: input.since } };
}

export function validateMutation(input: unknown): Validated<Mutation> {
  if (!isRecord(input)) return fail("not an object");
  if (input.type !== "mutate") return fail("type is not mutate");
  if (!isSeq(input.protocolVersion)) return fail("protocolVersion");
  if (!isId(input.listId)) return fail("listId");
  if (!isId(input.entityId)) return fail("entityId");
  if (!isId(input.idempotencyKey)) return fail("idempotencyKey");
  if (!isId(input.deviceId)) return fail("deviceId");
  if (!isRecord(input.patch)) return fail("patch");

  const base = {
    type: "mutate" as const,
    protocolVersion: input.protocolVersion,
    listId: input.listId,
    entityId: input.entityId,
    idempotencyKey: input.idempotencyKey,
    deviceId: input.deviceId,
  };

  if (input.entityType === "task") {
    const patch = validateTaskPatch(input.patch);
    if (!patch.ok) return patch;
    return { ok: true, value: { ...base, entityType: "task", patch: patch.value } };
  }
  if (input.entityType === "list") {
    const patch = validateListPatch(input.patch);
    if (!patch.ok) return patch;
    return { ok: true, value: { ...base, entityType: "list", patch: patch.value } };
  }
  return fail("entityType");
}

/**
 * Unknown keys are dropped, never rejected (F2) — that is what makes an
 * additive protocol change safe for a server that has not been redeployed.
 * An *invalid* value for a known key is still a rejection.
 */
export function validateTaskPatch(input: unknown): Validated<TaskPatch> {
  if (!isRecord(input)) return fail("patch is not an object");
  // Built as a bag because exactOptionalPropertyTypes forbids assigning
  // `undefined` to an optional key; only keys actually present are set.
  const patch: Record<string, unknown> = {};

  if ("title" in input) {
    if (!isBoundedString(input.title, MAX_TITLE_LENGTH)) return fail("title");
    patch.title = input.title;
  }
  if ("done" in input) {
    if (typeof input.done !== "boolean") return fail("done");
    patch.done = input.done;
  }
  if ("starred" in input) {
    if (typeof input.starred !== "boolean") return fail("starred");
    patch.starred = input.starred;
  }
  if ("position" in input) {
    if (!isBoundedString(input.position, MAX_POSITION_LENGTH)) return fail("position");
    if (input.position === "") return fail("position is empty");
    patch.position = input.position;
  }
  if ("deletedAt" in input) {
    if (input.deletedAt !== null && !isIsoTimestamp(input.deletedAt)) {
      return fail("deletedAt");
    }
    patch.deletedAt = input.deletedAt;
  }

  // Every key above was checked against the type it is declared with.
  return { ok: true, value: patch as TaskPatch };
}

export function validateListPatch(input: unknown): Validated<ListPatch> {
  if (!isRecord(input)) return fail("patch is not an object");
  const patch: Record<string, unknown> = {};

  if ("title" in input) {
    if (!isBoundedString(input.title, MAX_TITLE_LENGTH)) return fail("title");
    patch.title = input.title;
  }
  if ("backgroundPhotoUrl" in input) {
    const url = input.backgroundPhotoUrl;
    if (url !== null && !isBoundedString(url, MAX_URL_LENGTH)) return fail("backgroundPhotoUrl");
    patch.backgroundPhotoUrl = url;
  }
  if ("deletedAt" in input) {
    if (input.deletedAt !== null && !isIsoTimestamp(input.deletedAt)) {
      return fail("deletedAt");
    }
    patch.deletedAt = input.deletedAt;
  }

  // See validateTaskPatch.
  return { ok: true, value: patch as ListPatch };
}

/**
 * Length is counted in code points, not UTF-16 units, so an emoji costs what a
 * user would say it costs. `"🍖".length` is 2, which would make the bound mean
 * something different for Afrikaans and English text than for a shopping list
 * with emoji in it.
 */
function isBoundedString(value: unknown, max: number): value is string {
  return typeof value === "string" && [...value].length <= max;
}

// --- auth (L1-L3) ---------------------------------------------------------

/**
 * Deliberately not an RFC 5322 regex. The only thing this server does with an
 * email is use it as a lookup key, so the useful checks are "non-empty",
 * "bounded", and "has the shape a human would recognise as an address".
 * A stricter pattern rejects valid addresses and buys nothing here.
 */
function isEmail(value: unknown): value is string {
  return (
    typeof value === "string" &&
    value.length > 0 &&
    value.length <= MAX_EMAIL_LENGTH &&
    /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value)
  );
}

function isPassword(value: unknown): value is string {
  return (
    typeof value === "string" &&
    value.length >= MIN_PASSWORD_LENGTH &&
    value.length <= MAX_PASSWORD_LENGTH
  );
}

export function validateLoginRequest(input: unknown): Validated<LoginRequest> {
  if (!isRecord(input)) return fail("not an object");
  if (!isEmail(input.email)) return fail("email");
  // Length-checked, not strength-checked: rejecting a *login* for a short
  // password would tell an attacker their guess was too short to be real.
  if (typeof input.password !== "string" || input.password.length === 0) {
    return fail("password");
  }
  if (input.password.length > MAX_PASSWORD_LENGTH) return fail("password");
  if (!isId(input.deviceId)) return fail("deviceId");
  return {
    ok: true,
    value: {
      email: input.email,
      password: input.password,
      deviceId: input.deviceId,
    },
  };
}

/**
 * Public registration (L2, ADR 0004). The minimum length *is* enforced here —
 * the asymmetry with [validateLoginRequest] is deliberate and is the rule, not
 * an oversight.
 */
export function validateRegisterRequest(input: unknown): Validated<RegisterRequest> {
  if (!isRecord(input)) return fail("not an object");
  if (!isEmail(input.email)) return fail("email");
  if (!isPassword(input.password)) return fail("password too short or too long");
  if (!isId(input.deviceId)) return fail("deviceId");
  return {
    ok: true,
    value: {
      email: input.email,
      password: input.password,
      deviceId: input.deviceId,
    },
  };
}

/** Account creation, where the minimum length *is* enforced (L2). */
export function validateCreateUserRequest(input: unknown): Validated<{
  email: string;
  password: string;
}> {
  if (!isRecord(input)) return fail("not an object");
  if (!isEmail(input.email)) return fail("email");
  if (!isPassword(input.password)) return fail("password too short or too long");
  return { ok: true, value: { email: input.email, password: input.password } };
}

export function validateRequestMagicLinkRequest(
  input: unknown,
): Validated<RequestMagicLinkRequest> {
  if (!isRecord(input)) return fail("not an object");
  if (!isEmail(input.email)) return fail("email");
  return { ok: true, value: { email: input.email } };
}

/**
 * Bounded like `validateRefreshRequest`'s token, not shaped like a password
 * or an OTP code: the wire value is a `generateRefreshToken()` output (43
 * unpadded-base64url characters), but nothing here assumes that exact
 * length — only that it cannot be unbounded before it reaches
 * `crypto.subtle`.
 */
export function validateVerifyMagicLinkRequest(input: unknown): Validated<VerifyMagicLinkRequest> {
  if (!isRecord(input)) return fail("not an object");
  const token = input.token;
  if (typeof token !== "string" || token.length === 0 || token.length > 512) {
    return fail("token");
  }
  if (!isId(input.deviceId)) return fail("deviceId");
  return { ok: true, value: { token, deviceId: input.deviceId } };
}

export function validateRefreshRequest(input: unknown): Validated<RefreshRequest> {
  if (!isRecord(input)) return fail("not an object");
  const token = input.refreshToken;
  if (typeof token !== "string" || token.length === 0 || token.length > 512) {
    return fail("refreshToken");
  }
  if (!isId(input.deviceId)) return fail("deviceId");
  return { ok: true, value: { refreshToken: token, deviceId: input.deviceId } };
}

export function validateCreateInviteRequest(input: unknown): Validated<CreateInviteRequest> {
  if (!isRecord(input)) return fail("not an object");
  if (!isId(input.listId)) return fail("listId");
  if (!isEmail(input.email)) return fail("email");
  if (!isBoundedString(input.listTitle, MAX_TITLE_LENGTH)) return fail("listTitle");
  return {
    ok: true,
    value: { listId: input.listId, email: input.email, listTitle: input.listTitle },
  };
}

export function validateAcceptInviteRequest(input: unknown): Validated<AcceptInviteRequest> {
  if (!isRecord(input)) return fail("not an object");
  const token = input.inviteToken;
  // A JWT, so bounded generously but bounded — an unbounded string reaches
  // crypto.subtle.verify, which is not where input size should first be met.
  if (typeof token !== "string" || token.length === 0 || token.length > 4096) {
    return fail("inviteToken");
  }
  return { ok: true, value: { inviteToken: token } };
}

/**
 * `POST /auth/memberships/position`. The position is checked for shape here and
 * for *legality* nowhere: an unparseable key sorts somewhere harmless in one
 * person's own list and cannot corrupt anybody else's, so a bound is the whole
 * boundary check this needs (F3).
 */
export function validateSetListPositionRequest(input: unknown): Validated<SetListPositionRequest> {
  if (!isRecord(input)) return fail("not an object");
  if (!isId(input.listId)) return fail("listId");
  if (!isBoundedString(input.position, MAX_POSITION_LENGTH)) return fail("position");
  if (input.position === "") return fail("position is empty");
  return { ok: true, value: { listId: input.listId, position: input.position } };
}

/**
 * `POST /devices/token` (M2). There is deliberately no `deviceId` here — the
 * one the token is filed under comes from the caller's access token, so a
 * client cannot register a push token against somebody else's device.
 */
export function validateRegisterDeviceRequest(input: unknown): Validated<RegisterDeviceRequest> {
  if (!isRecord(input)) return fail("not an object");
  const token = input.fcmToken;
  if (typeof token !== "string" || token.length === 0 || token.length > MAX_FCM_TOKEN_LENGTH) {
    return fail("fcmToken");
  }
  return { ok: true, value: { fcmToken: token } };
}
