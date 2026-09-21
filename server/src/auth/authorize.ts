/**
 * The single place the Worker decides whether a request may touch a list.
 *
 * `ListRoom` never checks membership itself (L3, docs/adr/0002-authentication.md):
 * the Worker authorizes against `UsersRoom` first, so the rule lives in one
 * place and cannot drift between two implementations.
 */
import type { Membership } from "@dielys/protocol";
import { usersRoom } from "../do/rooms.js";
import { bearerToken, verifyAccessToken } from "./jwt.js";

export interface Principal {
  userId: string;
  deviceId: string;
}

export type AuthFailure = { ok: false; code: "unauthorized" | "forbidden"; status: 401 | 403 };
export type AuthResult<T> = { ok: true; value: T } | AuthFailure;

/**
 * Proves who is calling. Says nothing about what they may reach — a valid
 * token for a list you are not a member of still fails `authorizeListAccess`.
 */
export async function authenticate(request: Request, env: Env): Promise<AuthResult<Principal>> {
  const token = bearerToken(request);
  if (token === null) return { ok: false, code: "unauthorized", status: 401 };

  const verified = await verifyAccessToken(token, env.JWT_SIGNING_KEY);
  if (!verified.ok) return { ok: false, code: "unauthorized", status: 401 };

  return { ok: true, value: { userId: verified.claims.sub, deviceId: verified.claims.deviceId } };
}

/**
 * Authentication plus membership, in that order. A request that reaches a
 * `ListRoom` has passed both.
 *
 * `wsTicket` is only ever passed for the WebSocket upgrade route: a browser
 * cannot set `Authorization` on that one request the way every other route
 * (and OkHttp's upgrade) can, so it falls back to a one-time `?ticket=`
 * (ADR 0009) when there is no bearer token. `viaTicket` on the result says
 * which path was used, so the caller can trust the ticket's bound device id
 * over whatever `?deviceId=` the client itself put on the URL.
 */
export async function authorizeListAccess(
  request: Request,
  env: Env,
  listId: string,
  wsTicket?: { url: URL; now: number },
): Promise<AuthResult<{ principal: Principal; membership: Membership; viaTicket: boolean }>> {
  const viaTicket = bearerToken(request) === null;
  const authenticated = viaTicket
    ? await authenticateViaTicket(env, wsTicket)
    : await authenticate(request, env);
  if (!authenticated.ok) return authenticated;

  const membership = await usersRoom(env).checkMembership(authenticated.value.userId, listId);
  if (membership === null) {
    // 403, not 404: a member and a non-member must not be able to tell from
    // the response whether a given list id exists.
    return { ok: false, code: "forbidden", status: 403 };
  }

  return { ok: true, value: { principal: authenticated.value, membership, viaTicket } };
}

async function authenticateViaTicket(
  env: Env,
  wsTicket: { url: URL; now: number } | undefined,
): Promise<AuthResult<Principal>> {
  if (wsTicket === undefined) return { ok: false, code: "unauthorized", status: 401 };

  const ticket = wsTicket.url.searchParams.get("ticket");
  if (ticket === null) return { ok: false, code: "unauthorized", status: 401 };

  const redeemed = await usersRoom(env).redeemWsTicket(ticket, wsTicket.now);
  if (!redeemed.ok) return { ok: false, code: "unauthorized", status: 401 };

  return { ok: true, value: redeemed.value };
}

/**
 * The admin surface `scripts/create-user.ts` calls (L2). Guarded by a shared
 * secret rather than a user session, because it is what creates the first user
 * and so cannot require one.
 *
 * Fails closed when `ADMIN_TOKEN` is unset or empty: an unset secret must mean
 * "nobody", never "everybody".
 */
export function authorizeAdmin(request: Request, env: Env): boolean {
  const expected = env.ADMIN_TOKEN;
  if (typeof expected !== "string" || expected.length === 0) return false;

  const presented = bearerToken(request);
  if (presented === null) return false;

  return timingSafeEqual(presented, expected);
}

function timingSafeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i += 1) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}
