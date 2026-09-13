# 0007 — Deleting an account erases it; owned shared lists pass on

**Status:** Accepted
**Date:** 2026-09-13
**Builds on:** [ADR 0002](0002-authentication.md) (accounts, sessions, membership),
[ADR 0006](0006-owner-only-list-delete.md) (roles reach the room), and
[L3](../CODE_STANDARD.md#standard-l3). Carves account records out of
[F5.3](../CODE_STANDARD.md#standard-f5)'s tombstone rule.

## Context

Google Play requires an app that lets people create an account to let them delete it, from
inside the app and from a web page. POPIA gives the same person a right to have their personal
information deleted. Neither is satisfied by marking a row: the email address has to be gone.

Until now nothing in Dielys erased anything a person had given it. F5.3 says deletes are
tombstones, and that is right for list entities — a tombstone is how a phone that was offline
learns an item went away. It was never about accounts: a `users` row does not sync to anybody,
and a tombstoned account is still an email address on a server.

The hard part is lists. A person's account touches three kinds:

1. **Lists only they are on.** Nobody else can reach them. Keeping them keeps personal content
   for no one.
2. **Lists they are on that someone else owns.** The owner's list; this person's membership
   is the only thing that is theirs.
3. **Lists they own that others are on.** Deleting these would take a shared shopping list away
   from the rest of a household because one person left the app. Keeping them with no owner
   leaves a list nobody can share or delete again (`removeMembership` refuses to let an owner
   leave for exactly that reason).

## Decision

### `DELETE /account` erases the account in one `UsersRoom` transaction

`UsersRoom.deleteAccount(userId)`, authenticated by the caller's own access token:

- For every list the user **owns** that has other members, the member with the oldest
  membership (`created_at`, then `user_id`) becomes `owner`. Longest on the list is the one
  rule that needs no input from anybody and cannot change under them.
- Every list with **no other member** is collected for erasure, and its `list_heads` row goes.
- The user's memberships, refresh tokens, device rows (FCM tokens), any pending magic link for
  their email, and the `users` row itself are **hard-deleted** — `DELETE FROM`, not tombstones.

One transaction, so a failure part-way leaves the account exactly as it was. Deleting an
account that is already gone succeeds and does nothing, so a client that lost the first
response can retry safely.

### The Worker erases the rooms nobody is left on, and resets sockets on the rest

`UsersRoom` answers with two lists of ids: the ones to erase and the ones still shared. The
Worker — which already resolves which DO to talk to (D1) — then:

- calls `ListRoom.erase()` on each unshared list: every socket closed, then
  `ctx.storage.deleteAll()`. The schema is re-created empty so the object stays usable, but
  nothing of the list survives.
- calls `ListRoom.disconnectAll()` on each list still shared. That closes the deleted user's
  own open sockets — which the Worker authorized when they opened and will never see again —
  and every other member's too, so their clients reconnect and are re-authorized with the role
  they hold *now*. That second part is what fixes ADR 0006's recorded gap for the one case
  where a role does change: a newly promoted owner's socket would otherwise still carry
  `member` until it happened to reconnect.

Items the deleted user wrote on a shared list stay on it. They carry a device id, never a user
id or an email, and they are part of a list other people still have.

### An invite to a list nobody is on is refused

An invite token is a signed JWT that outlives nothing it refers to. Accepting one for a list
whose members are all gone would make the acceptor a `member` of an erased list with no owner.
`addMembership` refuses (`forbidden`) when the list has no members.

## Consequences

- An erased list's room is not reachable again unless someone claims the same UUIDv7, which in
  practice means never. A room whose `erase()` call fails is logged with its list id and left;
  no membership points at it, so no client can read it, but its storage lingers until someone
  removes it by hand. Rare (a DO-to-DO call failing just after a committed transaction), and
  recorded rather than solved with a retry queue.
- The deleted person's other phones find out on their next refresh, which fails and signs them
  out. Their local copy of the lists stays on those phones; it is theirs.
- The phone that asked for the deletion wipes its own local database as well, since the user
  asked for their data to be gone, not merely to be signed out.
- The same email address can sign up again afterwards and gets a new, empty account. Nothing
  links the two.
- Rate-limit buckets are keyed hashes of an address, not of an account, and expire within a
  day. They are left to expire.

## Alternatives

- **Delete every list the user owns, shared or not.** Simplest, and a household loses its
  shared list because one member left. Rejected.
- **Refuse to delete until the user has handed over or deleted each shared list.** Honest, but
  unusable from the web deletion page, where there is no list UI, and a blocker Play reviewers
  would reasonably read as obstruction.
- **Tombstone the account.** Keeps F5.3 uniform, but the email address stays on the server,
  which is the thing being asked for.
