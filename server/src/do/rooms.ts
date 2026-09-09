/**
 * How to reach a Durable Object by name. Routing only — no rules (D1).
 *
 * It lives here rather than in `auth/` because the Worker is not the only
 * caller any more: `ListRoom` reaches `UsersRoom` to fan a wake push out to the
 * devices that did not get the change over a socket (M2).
 */
import type { UsersRoom } from "./UsersRoom.js";

/** The name the singleton is addressed by. Changing it orphans every account. */
const USERS_ROOM_NAME = "users-v1";

export function usersRoom(env: Env): DurableObjectStub<UsersRoom> {
  // A singleton by construction: one fixed name, so every Worker isolate in
  // every colo resolves the same object (L1).
  return env.USERS_ROOM.get(env.USERS_ROOM.idFromName(USERS_ROOM_NAME));
}
