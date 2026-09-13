# 0006 — Only the owner deletes a list; the room is told the caller's role

**Status:** Accepted
**Date:** 2026-09-13
**Builds on:** [ADR 0002](0002-authentication.md) and [L3](../CODE_STANDARD.md#standard-l3).
Narrows L3's "`ListRoom` MUST NOT check membership" without reversing it: the room still never
asks who is a member, but it now applies one rule to a role the Worker hands it.

## Context

A list delete is an ordinary list mutation — `deletedAt` set on the list, a tombstone that goes
down the changelog like any other change (F5.3). Every member's phone applies it. Membership was
the only thing checked, so **anybody a list had been shared with could delete it for everybody**,
the owner included, with no undo.

The household's rule is simpler than that: whoever made the list decides whether it exists.
Everybody else can leave it — `DELETE /lists/{id}/members/{self}`, which already existed (#60)
and removes only their own membership.

Where to enforce it is the real question. L3 puts all authorization in the Worker, against
`UsersRoom`, before anything reaches a `ListRoom`. That works for `POST /lists/{id}/mutate`,
whose body the Worker could read. It cannot work for the WebSocket: the Worker authorizes the
upgrade and then never sees a frame, and `mutate` is a valid frame (PROTOCOL.md "Messages").
The Android client never writes over the socket, but the server cannot rely on that — the rule
has to hold for any client.

## Decision

- **The Worker forwards the role it already resolved.** `authorizeListAccess` returns the
  caller's membership, role included. `doRequest` sets it on the room's request as `role`,
  with `searchParams.set` so a `?role=` the client typed itself is overwritten, not honoured.
  A Durable Object is reachable only through its binding, so nothing but the Worker can put a
  role on a room request.
- **The room remembers it for the life of a socket.** The upgrade attaches `role` to the
  socket's hibernation session; `hello` carries it over from that attachment and never reads a
  role from the message.
- **The rule is pure and lives in one place.** `server/src/domain/permissions.ts` `mayApply`
  refuses a list mutation that *sets* `deletedAt` unless the role is `owner`. Everything else is
  open to every member, as before. A missing or unrecognised role fails closed.
- **Refusal is `forbidden`** — 403 over HTTP, an `error` frame over the socket — with the
  mutation's idempotency key, so a client can tie the refusal to the outbox row that caused it.

## Consequences

- L3's membership rule still has exactly one implementation, in the Worker. What moved into the
  room is a *permission* over a fact the Worker established, and only because there is no other
  place that sees socket frames.
- An older client that still offers Delete to a member gets `forbidden`. Its outbox marks the row
  dead (never retried), and the list stays hidden on *that* phone, because the local tombstone
  was written before the refusal came back. Nobody else loses anything, which is the point.
  Clearing the app's data brings it back on that phone, since a fresh sync rebuilds the row
  from the server, which never deleted it.
- A role that changes while a socket is open is not re-read until the socket reconnects. Roles do
  not change today — there is no transfer of ownership — so this is recorded rather than solved.

## Alternatives

- **A dedicated `DELETE /lists/{id}` route checked in the Worker.** Keeps authorization entirely
  in the Worker for that route, but the socket would still accept a `mutate` that sets
  `deletedAt`, so the room would need the same rule anyway. Two write paths for one change.
- **Refuse all list mutations over the socket.** Breaks the protocol for a client that is
  allowed to write there, to avoid a check that costs one comparison.
