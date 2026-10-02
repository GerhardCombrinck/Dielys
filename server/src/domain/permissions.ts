/**
 * Pure. No imports from storage/, no env, no Date.now(), no crypto.randomUUID()
 * (D1). The role is an argument: the Worker resolved it against UsersRoom
 * before the request reached the room (L3, ADR 0006).
 */
import type { MembershipRole, Mutation } from "@dielys/protocol";

/**
 * Whether somebody on a list in `role` may make this change.
 *
 * Everything is open to every member except deleting or archiving the list
 * itself. A delete is a tombstone that goes down the changelog like any other
 * change, so it takes the list off every member's phone — and a person who was
 * invited onto a list is not the person who gets to end it for the one who made
 * it. Members leave instead, which removes only their own membership.
 *
 * `null` is a request that did not come through the Worker with a role on it,
 * and fails closed: an unknown caller does not get to delete anything.
 *
 * Only *setting* `deletedAt` counts. Tombstones are sticky (F5.3), so a patch
 * carrying `deletedAt: null` changes nothing and has no reason to be refused.
 *
 * Archiving follows the same reasoning (ADR 0014): it moves the list out of
 * the way on every member's screen, so it is the owner's to do — and to undo,
 * since `archived` is not sticky and `false` is as much a change as `true`.
 */
export function mayApply(role: MembershipRole | null, mutation: Mutation): boolean {
  if (mutation.entityType !== "list") return true;
  const deleting = typeof mutation.patch.deletedAt === "string";
  const archiving = mutation.patch.archived !== undefined;
  return (!deleting && !archiving) || role === "owner";
}

/** Reads a role handed over by the Worker, rejecting anything that is not one. */
export function parseRole(raw: string | null): MembershipRole | null {
  return raw === "owner" || raw === "member" ? raw : null;
}
