/**
 * The single place the Worker decides whether a request may touch a list.
 *
 * `ListRoom` never checks membership itself (L3, docs/adr/0002-authentication.md):
 * the Worker authorizes against `UsersRoom` first, so the rule lives in one
 * place and cannot drift between two implementations.
 */
import type { Membership } from "@dielys/protocol";
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

export function usersRoom(env: Env): DurableObjectStub<import("../do/UsersRoom.js").UsersRoom> {
  // A singleton by construction: one fixed name, so every Worker isolate in
  // every colo resolves the same object (L1).
  return env.USERS_ROOM.get(env.USERS_ROOM.idFromName("users-v1"));
}

/**
 * Authentication plus membership, in that order. A request that reaches a
 * `ListRoom` has passed both.
 */
export async function authorizeListAccess(
  request: Request,
  env: Env,
  listId: string,
): Promise<AuthResult<{ principal: Principal; membership: Membership }>> {
  const authenticated = await authenticate(request, env);
  if (!authenticated.ok) return authenticated;

  const membership = await usersRoom(env).checkMembership(authenticated.value.userId, listId);
  if (membership === null) {
    // 403, not 404: a member and a non-member must not be able to tell from
    // the response whether a given list id exists.
    return { ok: false, code: "forbidden", status: 403 };
  }

  return { ok: true, value: { principal: authenticated.value, membership } };
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
