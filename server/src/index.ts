/**
 * Worker entry. Routing and auth only, no business logic (D1) — authenticate,
 * resolve which DO to talk to, forward.
 */
import type { ErrorCode } from "@dielys/protocol";
import { authenticate, authorizeAdmin, authorizeListAccess } from "./auth/authorize.js";
import {
  ACCESS_TOKEN_TTL_SECONDS,
  isUsableSigningKey,
  signAccessToken,
  verifyInviteToken,
} from "./auth/jwt.js";
import { clientAddress, clientKey } from "./auth/ratelimit.js";
import { ListRoom } from "./do/ListRoom.js";
import { usersRoom } from "./do/rooms.js";
import { UsersRoom } from "./do/UsersRoom.js";
import {
  parseJson,
  validateAcceptInviteRequest,
  validateCreateInviteRequest,
  validateCreateUserRequest,
  validateLoginRequest,
  validateRefreshRequest,
  validateRegisterDeviceRequest,
  validateRegisterRequest,
  validateRequestMagicLinkRequest,
  validateSetListPositionRequest,
  validateVerifyMagicLinkRequest,
} from "./domain/validate.js";
import { log } from "./lib/log.js";

export { ListRoom, UsersRoom };

/** `/lists/{listId}/{action}` — the only shape that reaches a ListRoom. */
const LIST_ROUTE = /^\/lists\/([^/]+)\/(ws|changes|mutate)$/;
/** `/lists/{listId}` — claim or invite, handled by the Worker itself. */
const LIST_ROOT_ROUTE = /^\/lists\/([^/]+)$/;
const INVITE_ROUTE = /^\/lists\/([^/]+)\/invite$/;

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    // D3: one clock reading per request, passed down.
    const now = Date.now();

    try {
      if (url.pathname === "/health") {
        return Response.json({ ok: true, environment: env.ENVIRONMENT });
      }

      // Served ahead of the signing-key gate below: Android's App Link
      // verifier fetches this before anyone has a session, and it carries no
      // user content (ADR 0005) — there is nothing for the gate to protect.
      if (url.pathname === "/.well-known/assetlinks.json") {
        return Response.json(androidAssetLinks(env));
      }

      // Fail closed on a missing or weak signing key. Without this the Worker
      // would sign and verify tokens with the literal string "undefined" —
      // valid-looking sessions anyone could forge. A deployment that has not
      // had `wrangler secret put JWT_SIGNING_KEY` run against it must serve
      // nothing but /health.
      if (!isUsableSigningKey(env.JWT_SIGNING_KEY)) {
        log("error", "worker.signing-key.unusable", { path: url.pathname });
        return errorResponse("internal", 503);
      }

      switch (url.pathname) {
        case "/auth/register":
          return await handleRegister(request, env, now);
        case "/auth/login":
          return await handleLogin(request, env, now);
        case "/auth/magic/request":
          return await handleRequestMagicLink(request, env, now);
        case "/auth/magic/verify":
          return await handleVerifyMagicLink(request, env, now);
        case "/auth/magic/status":
          return await handleMagicLinkStatus(request, env, url, now);
        case "/magic":
          return magicLinkFallbackPage();
        case "/invite":
          return inviteLinkFallbackPage();
        case "/auth/refresh":
          return await handleRefresh(request, env, now);
        case "/auth/memberships":
          return await handleMemberships(request, env);
        case "/auth/memberships/position":
          return await handleSetListPosition(request, env);
        case "/devices/token":
          return await handleRegisterDevice(request, env, now);
        case "/admin/users":
          return await handleCreateUser(request, env, now);
        case "/invites/accept":
          return await handleAcceptInvite(request, env, now);
      }

      const invite = INVITE_ROUTE.exec(url.pathname);
      if (invite !== null) {
        return await handleCreateInvite(request, env, decodeURIComponent(invite[1] as string), now);
      }

      const claim = LIST_ROOT_ROUTE.exec(url.pathname);
      if (claim !== null) {
        return await handleClaimList(request, env, decodeURIComponent(claim[1] as string), now);
      }

      const route = LIST_ROUTE.exec(url.pathname);
      if (route === null) return errorResponse("malformed", 404);

      // Checked by the regex: groups 1 and 2 exist whenever it matches.
      const listId = decodeURIComponent(route[1] as string);
      const action = route[2] as string;

      const auth = await authorizeListAccess(request, env, listId);
      if (!auth.ok) {
        log("info", "worker.denied", { listId, action, code: auth.code });
        return errorResponse(auth.code, auth.status);
      }

      // D3: one DO per list, addressed by name. Never a random id.
      const stub = env.LIST_ROOM.get(env.LIST_ROOM.idFromName(listId));
      return await stub.fetch(doRequest(request, url, listId, action));
    } catch (error) {
      // D4: never leak a stack or an internal message to a client.
      log("error", "worker.unhandled", { path: url.pathname, error: String(error) });
      return errorResponse("internal", 500);
    }
  },
} satisfies ExportedHandler<Env>;

// --- auth routes ----------------------------------------------------------

/**
 * Public registration (L2, ADR 0004). Succeeds into a session, so a new account
 * is a signed-in account and the client never makes two calls to get one.
 *
 * 409 for a taken email is an account-enumeration oracle, knowingly: there is no
 * honest alternative without email verification, and the rate limit is the
 * control. Login stays non-enumerable, which is the half that matters.
 */
async function handleRegister(request: Request, env: Env, now: number): Promise<Response> {
  if (request.method !== "POST") return errorResponse("malformed", 405);

  const body = await readJson(request);
  if (body === null) return errorResponse("malformed", 400);

  const parsed = validateRegisterRequest(body);
  if (!parsed.ok) return errorResponse("malformed", 400);

  const result = await usersRoom(env).register(
    parsed.value.email,
    parsed.value.password,
    parsed.value.deviceId,
    await bucketKey(request, env),
    now,
  );
  if (!result.ok) {
    // The email is never logged — user content (D4).
    log("info", "worker.register.rejected", { code: result.code });
    return errorResponse(result.code, registerStatus(result.code));
  }

  return Response.json(
    await tokenPair(
      result.value.userId,
      parsed.value.deviceId,
      result.value.refreshToken,
      env,
      now,
    ),
    { status: 201 },
  );
}

function registerStatus(code: ErrorCode): number {
  if (code === "rate-limited") return 429;
  return code === "already-exists" ? 409 : 400;
}

/**
 * The caller, as the rate limiter sees them: a keyed hash of the address, never
 * the address (D4, ADR 0004). One HMAC, against PBKDF2's ten thousand rounds.
 */
async function bucketKey(request: Request, env: Env): Promise<string> {
  return clientKey(clientAddress(request), env.JWT_SIGNING_KEY);
}

async function handleLogin(request: Request, env: Env, now: number): Promise<Response> {
  const body = await readJson(request);
  if (body === null) return errorResponse("malformed", 400);

  const login = validateLoginRequest(body);
  if (!login.ok) return errorResponse("malformed", 400);

  const result = await usersRoom(env).login(
    login.value.email,
    login.value.password,
    login.value.deviceId,
    await bucketKey(request, env),
    now,
  );
  if (!result.ok) {
    // Never log the email — user content (D4). Never distinguish "no such
    // account" from "wrong password" in the response.
    log("info", "worker.login.rejected", { code: result.code });
    return errorResponse(result.code, result.code === "rate-limited" ? 429 : 401);
  }

  return Response.json(
    await tokenPair(result.value.userId, login.value.deviceId, result.value.refreshToken, env, now),
  );
}

/**
 * Fails closed the same way `isUsableSigningKey` does (ADR 0005): a
 * deployment with no Brevo credential configured must refuse `/auth/magic/*`
 * outright rather than let `UsersRoom` discover it mid-request, after the
 * rate limiters have already spent the caller's budget on a request that was
 * always going to fail.
 */
export function isUsableEmailConfig(env: Env): boolean {
  return (
    typeof env.BREVO_API_KEY === "string" &&
    env.BREVO_API_KEY.length > 0 &&
    typeof env.EMAIL_FROM === "string" &&
    env.EMAIL_FROM.length > 0
  );
}

/** `POST /auth/magic/request` (ADR 0005). Public, like registration — asking
 * for a link is not itself a sign of anything, and the response never
 * reveals whether the address has an account. */
async function handleRequestMagicLink(request: Request, env: Env, now: number): Promise<Response> {
  if (request.method !== "POST") return errorResponse("malformed", 405);
  if (!isUsableEmailConfig(env)) {
    log("error", "worker.magiclink.unconfigured", {});
    return errorResponse("internal", 503);
  }

  const body = await readJson(request);
  if (body === null) return errorResponse("malformed", 400);

  const parsed = validateRequestMagicLinkRequest(body);
  if (!parsed.ok) return errorResponse("malformed", 400);

  const result = await usersRoom(env).requestMagicLink(
    parsed.value.email,
    await bucketKey(request, env),
    now,
  );
  if (!result.ok) {
    log("info", "worker.magiclink.request.rejected", { code: result.code });
    return errorResponse(result.code, magicRequestStatus(result.code));
  }
  return Response.json(result.value);
}

function magicRequestStatus(code: ErrorCode): number {
  if (code === "rate-limited") return 429;
  return code === "internal" ? 503 : 400;
}

/**
 * `POST /auth/magic/verify` (ADR 0005). Creates the account on first use and
 * signs in on every use after — same collapse-into-one-call shape as
 * register.
 */
async function handleVerifyMagicLink(request: Request, env: Env, now: number): Promise<Response> {
  if (request.method !== "POST") return errorResponse("malformed", 405);

  const body = await readJson(request);
  if (body === null) return errorResponse("malformed", 400);

  const parsed = validateVerifyMagicLinkRequest(body);
  if (!parsed.ok) return errorResponse("malformed", 400);

  const result = await usersRoom(env).verifyMagicLink(
    parsed.value.token,
    parsed.value.deviceId,
    await bucketKey(request, env),
    now,
  );
  if (!result.ok) {
    log("info", "worker.magiclink.verify.rejected", { code: result.code });
    return errorResponse(result.code, magicVerifyStatus(result.code));
  }

  return Response.json(
    await tokenPair(
      result.value.userId,
      parsed.value.deviceId,
      result.value.refreshToken,
      env,
      now,
    ),
  );
}

function magicVerifyStatus(code: ErrorCode): number {
  if (code === "rate-limited") return 429;
  if (code === "invalid-token" || code === "token-expired") return 401;
  return 400;
}

/** Longer than `generateRefreshToken` ever produces (43 chars, unpadded
 * base64url of 32 bytes) — bounded at the boundary (F3) without needing to
 * know that length exactly. */
const MAX_REQUEST_ID_LENGTH = 128;

/**
 * `GET /auth/magic/status?requestId=…` (ADR 0005 follow-up). Unauthenticated
 * like the rest of `/auth/magic/*` — there is no session yet — and answers
 * `{ delivered: false }` for anything it cannot make sense of rather than an
 * error, the same shape `magicLinkStatus` itself uses.
 */
async function handleMagicLinkStatus(
  request: Request,
  env: Env,
  url: URL,
  now: number,
): Promise<Response> {
  if (request.method !== "GET") return errorResponse("malformed", 405);

  const requestId = url.searchParams.get("requestId");
  if (requestId === null || requestId.length === 0 || requestId.length > MAX_REQUEST_ID_LENGTH) {
    return errorResponse("malformed", 400);
  }

  const result = await usersRoom(env).magicLinkStatus(
    requestId,
    await bucketKey(request, env),
    now,
  );
  return Response.json(result);
}

/** Package name for `za.co.dielys` — public (it is the app's own id, already
 * in `android/app/build.gradle.kts`), so a constant rather than a secret. */
const ANDROID_PACKAGE_NAME = "za.co.dielys";

/**
 * `GET /.well-known/assetlinks.json` (ADR 0005) — what makes `https://dielys.com`
 * links (magic-link sign-in at `/magic`, list invites at `/invite`) Android App
 * Links instead of ordinary URLs. `handle_all_urls` below covers the whole
 * domain, so this one file authorizes both paths — Android fetches it once to
 * confirm the site endorses the app before it will open links from this
 * domain without asking.
 *
 * `ANDROID_CERT_SHA256_FINGERPRINTS` is comma-separated SHA-256 fingerprints
 * of the app's signing certificate(s) — public once published here, but not
 * yet known to this Worker until the release keystore's fingerprint is
 * generated and set (`wrangler secret put`). Absent, this still answers a
 * well-formed document with no fingerprints, which Android correctly treats
 * as "not verified" rather than the Worker throwing.
 */
function androidAssetLinks(env: Env): unknown[] {
  const raw = env.ANDROID_CERT_SHA256_FINGERPRINTS;
  const fingerprints =
    typeof raw === "string" && raw.trim().length > 0 ? raw.split(",").map((fp) => fp.trim()) : [];
  return [
    {
      relation: ["delegate_permission/common.handle_all_urls"],
      target: {
        namespace: "android_app",
        package_name: ANDROID_PACKAGE_NAME,
        sha256_cert_fingerprints: fingerprints,
      },
    },
  ];
}

/**
 * `GET /magic` (ADR 0005). Reached only when the App Link did not open the
 * app directly — Android has not verified the domain yet, or the link was
 * opened somewhere without Dielys installed. A minimal page beats a bare 404;
 * it carries no token handling of its own, since the token in the query
 * string is only useful to the app's own `/auth/magic/verify` call.
 */
function magicLinkFallbackPage(): Response {
  return htmlPage("<p>Open this link on your phone with Dielys installed.</p>");
}

/**
 * `GET /invite` (L3), the same App Link fallback as `/magic` above for a
 * tapped invite link: reached only when Android has not verified the domain
 * yet, or the link was opened somewhere without Dielys installed. The invite
 * token in the query string is a bearer credential (L3) with nothing for this
 * page to do with it — only the app's own `JoinDialog`/accept flow redeems it.
 */
function inviteLinkFallbackPage(): Response {
  return htmlPage("<p>Open this link on your phone with Dielys installed to join the list.</p>");
}

function htmlPage(body: string): Response {
  return new Response(
    `<!doctype html><html><head><meta charset="utf-8"><title>Dielys</title></head>` +
      `<body>${body}</body></html>`,
    { status: 200, headers: { "content-type": "text/html; charset=utf-8" } },
  );
}

async function handleRefresh(request: Request, env: Env, now: number): Promise<Response> {
  const body = await readJson(request);
  if (body === null) return errorResponse("malformed", 400);

  const refresh = validateRefreshRequest(body);
  if (!refresh.ok) return errorResponse("malformed", 400);

  const result = await usersRoom(env).rotateRefreshToken(
    refresh.value.refreshToken,
    refresh.value.deviceId,
    now,
  );
  if (!result.ok) return errorResponse(result.code, 401);

  return Response.json(
    await tokenPair(
      result.value.userId,
      refresh.value.deviceId,
      result.value.refreshToken,
      env,
      now,
    ),
  );
}

async function handleMemberships(request: Request, env: Env): Promise<Response> {
  const auth = await authenticate(request, env);
  if (!auth.ok) return errorResponse(auth.code, auth.status);

  const memberships = await usersRoom(env).listMemberships(auth.value.userId);
  return Response.json({ memberships });
}

/**
 * Moves one list in the caller's own ordering (PROTOCOL.md "Ordering the
 * lists"). It touches the caller's membership row and nothing on the list, so
 * it never reaches a `ListRoom` and the other member never hears about it.
 */
async function handleSetListPosition(request: Request, env: Env): Promise<Response> {
  if (request.method !== "POST") return errorResponse("malformed", 405);

  const auth = await authenticate(request, env);
  if (!auth.ok) return errorResponse(auth.code, auth.status);

  const body = await readJson(request);
  if (body === null) return errorResponse("malformed", 400);

  const parsed = validateSetListPositionRequest(body);
  if (!parsed.ok) return errorResponse("malformed", 400);

  const result = await usersRoom(env).setListPosition(
    auth.value.userId,
    parsed.value.listId,
    parsed.value.position,
  );
  // 403 for a list the caller is not on, never 404 (L3).
  if (!result.ok) return errorResponse(result.code, 403);

  return Response.json(result.value);
}

/**
 * Account creation (L2). There is no public registration endpoint — this is
 * guarded by ADMIN_TOKEN and reached only by scripts/create-user.ts.
 */
async function handleCreateUser(request: Request, env: Env, now: number): Promise<Response> {
  if (request.method !== "POST") return errorResponse("malformed", 405);
  if (!authorizeAdmin(request, env)) {
    log("warn", "worker.admin.denied", {});
    return errorResponse("unauthorized", 401);
  }

  const body = await readJson(request);
  if (body === null) return errorResponse("malformed", 400);

  const create = validateCreateUserRequest(body);
  if (!create.ok) return errorResponse("malformed", 400);

  const result = await usersRoom(env).createUser(create.value.email, create.value.password, now);
  if (!result.ok) return errorResponse(result.code, result.code === "already-exists" ? 409 : 400);

  return Response.json({ userId: result.value }, { status: 201 });
}

/**
 * Files this device's FCM token so a change made on the other phone can wake
 * this one (M2).
 *
 * The device id is taken from the access token's `deviceId` claim and never
 * from the body. A client that could name its own device id could register a
 * push token against somebody else's phone, which would let it be woken — and
 * eventually replaced — by an account that does not own it.
 */
async function handleRegisterDevice(request: Request, env: Env, now: number): Promise<Response> {
  if (request.method !== "POST") return errorResponse("malformed", 405);

  const auth = await authenticate(request, env);
  if (!auth.ok) return errorResponse(auth.code, auth.status);

  const body = await readJson(request);
  if (body === null) return errorResponse("malformed", 400);

  const parsed = validateRegisterDeviceRequest(body);
  if (!parsed.ok) return errorResponse("malformed", 400);

  await usersRoom(env).registerDevice(
    auth.value.userId,
    auth.value.deviceId,
    parsed.value.fcmToken,
    now,
  );
  return new Response(null, { status: 204 });
}

// --- list membership routes ----------------------------------------------

/**
 * Claims a client-generated list id as owner (F5.1 — the server never mints
 * an id, so ownership is claimed rather than granted at creation).
 */
async function handleClaimList(
  request: Request,
  env: Env,
  listId: string,
  now: number,
): Promise<Response> {
  if (request.method !== "POST") return errorResponse("malformed", 405);

  const auth = await authenticate(request, env);
  if (!auth.ok) return errorResponse(auth.code, auth.status);

  const result = await usersRoom(env).claimList(auth.value.userId, listId, now);
  if (!result.ok) return errorResponse(result.code, 403);

  return Response.json({ listId, role: "owner", alreadyMember: result.value.alreadyMember });
}

/** L3: only the owner may invite. Mails the invite itself (ADR 0005's
 * email infra, reused) — the owner's own device never sees the bearer
 * token, only whether the send worked. */
async function handleCreateInvite(
  request: Request,
  env: Env,
  listId: string,
  now: number,
): Promise<Response> {
  if (request.method !== "POST") return errorResponse("malformed", 405);
  if (!isUsableEmailConfig(env)) {
    log("error", "worker.invite.unconfigured", {});
    return errorResponse("internal", 503);
  }

  const auth = await authorizeListAccess(request, env, listId);
  if (!auth.ok) return errorResponse(auth.code, auth.status);
  if (auth.value.membership.role !== "owner") return errorResponse("forbidden", 403);

  const body = await readJson(request);
  if (body === null) return errorResponse("malformed", 400);
  const parsed = validateCreateInviteRequest(body);
  if (!parsed.ok || parsed.value.listId !== listId) return errorResponse("malformed", 400);

  const result = await usersRoom(env).sendInviteEmail(
    auth.value.principal.userId,
    listId,
    parsed.value.listTitle,
    parsed.value.email,
    now,
  );
  if (!result.ok) {
    return errorResponse(result.code, result.code === "rate-limited" ? 429 : 503);
  }
  return Response.json({ expiresIn: result.value.expiresIn });
}

async function handleAcceptInvite(request: Request, env: Env, now: number): Promise<Response> {
  if (request.method !== "POST") return errorResponse("malformed", 405);

  const auth = await authenticate(request, env);
  if (!auth.ok) return errorResponse(auth.code, auth.status);

  const body = await readJson(request);
  if (body === null) return errorResponse("malformed", 400);

  const parsed = validateAcceptInviteRequest(body);
  if (!parsed.ok) return errorResponse("malformed", 400);

  const invite = await verifyInviteToken(parsed.value.inviteToken, env.JWT_SIGNING_KEY, now);
  if (!invite.ok) {
    // `wrong-type` here means someone presented an access token as an invite —
    // the reason the two claim shapes are distinct (L3).
    log("info", "worker.invite.rejected", { reason: invite.reason });
    return errorResponse(invite.reason === "expired" ? "token-expired" : "unauthorized", 401);
  }

  const result = await usersRoom(env).addMembership(
    auth.value.userId,
    invite.claims.listId,
    "member",
    now,
    invite.claims.email,
  );
  if (!result.ok) return errorResponse(result.code, result.code === "forbidden" ? 403 : 404);

  return Response.json({
    listId: invite.claims.listId,
    role: "member",
    alreadyMember: result.value.alreadyMember,
  });
}

// --- helpers --------------------------------------------------------------

async function tokenPair(
  userId: string,
  deviceId: string,
  refreshToken: string,
  env: Env,
  now: number,
) {
  return {
    accessToken: await signAccessToken({ sub: userId, deviceId }, env.JWT_SIGNING_KEY, now),
    refreshToken,
    // Seconds, not a timestamp: client clocks are not trusted (F5.9).
    expiresIn: ACCESS_TOKEN_TTL_SECONDS,
    userId,
  };
}

async function readJson(request: Request): Promise<unknown> {
  const parsed = parseJson(await request.text());
  return parsed.ok ? parsed.value : null;
}

/**
 * The DO's internal surface is flat (`/ws`, `/changes`, `/mutate`) and takes
 * `listId` as a query parameter, because a DO cannot recover the name it was
 * addressed by.
 */
function doRequest(request: Request, url: URL, listId: string, action: string): Request {
  const target = new URL(url);
  target.pathname = `/${action}`;
  target.searchParams.set("listId", listId);
  return new Request(target, request);
}

function errorResponse(code: ErrorCode, status: number): Response {
  // D4: a stable machine-readable code, no internal detail, no stack.
  return new Response(JSON.stringify({ type: "error", code, idempotencyKey: null }), {
    status,
    headers: { "content-type": "application/json" },
  });
}
