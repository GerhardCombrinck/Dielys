/**
 * Worker entry. Routing and auth only, no business logic (D1) — authenticate,
 * resolve which DO to talk to, forward.
 */
import type { ErrorCode } from "@dielys/protocol";
import { authenticate, authorizeAdmin, authorizeListAccess } from "./auth/authorize.js";
import {
  ACCESS_TOKEN_TTL_SECONDS,
  INVITE_TOKEN_TTL_SECONDS,
  isUsableSigningKey,
  signAccessToken,
  signInviteToken,
  verifyInviteToken,
} from "./auth/jwt.js";
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
        case "/auth/login":
          return await handleLogin(request, env, now);
        case "/auth/refresh":
          return await handleRefresh(request, env, now);
        case "/auth/memberships":
          return await handleMemberships(request, env);
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

async function handleLogin(request: Request, env: Env, now: number): Promise<Response> {
  const body = await readJson(request);
  if (body === null) return errorResponse("malformed", 400);

  const login = validateLoginRequest(body);
  if (!login.ok) return errorResponse("malformed", 400);

  const result = await usersRoom(env).login(
    login.value.email,
    login.value.password,
    login.value.deviceId,
    now,
  );
  if (!result.ok) {
    // Never log the email — user content (D4). Never distinguish "no such
    // account" from "wrong password" in the response.
    log("info", "worker.login.rejected", { code: result.code });
    return errorResponse(result.code, 401);
  }

  return Response.json(
    await tokenPair(result.value.userId, login.value.deviceId, result.value.refreshToken, env, now),
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

/** L3: only the owner may invite. */
async function handleCreateInvite(
  request: Request,
  env: Env,
  listId: string,
  now: number,
): Promise<Response> {
  if (request.method !== "POST") return errorResponse("malformed", 405);

  const auth = await authorizeListAccess(request, env, listId);
  if (!auth.ok) return errorResponse(auth.code, auth.status);
  if (auth.value.membership.role !== "owner") return errorResponse("forbidden", 403);

  // Body is optional — the list id is already in the path. Accept one for
  // symmetry with the protocol type, but reject it if it disagrees.
  const body = await readJson(request);
  if (body !== null && Object.keys(body as object).length > 0) {
    const parsed = validateCreateInviteRequest(body);
    if (!parsed.ok || parsed.value.listId !== listId) return errorResponse("malformed", 400);
  }

  const inviteToken = await signInviteToken(
    { listId, sub: auth.value.principal.userId },
    env.JWT_SIGNING_KEY,
    now,
  );
  return Response.json({ inviteToken, expiresIn: INVITE_TOKEN_TTL_SECONDS });
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
  );
  if (!result.ok) return errorResponse(result.code, 404);

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
