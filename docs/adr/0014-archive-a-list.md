# 0014 — A list can be archived instead of deleted, by its owner, for everybody on it

**Status:** Accepted
**Date:** 2026-10-02
**Builds on:** [ADR 0006](0006-owner-only-list-delete.md). Adds a second owner-only list change
beside delete; changes nothing about how either is enforced.

## Context

A packing list for a yearly trip, a braai list, a Christmas list: made once, used again. Today
the only way to get one off the screen between uses is to delete it, which loses it — so people
either keep a screenful of lists they are not using, or retype them every time.

What people asked for: "archive a list for next year instead of delete, or hide a list instead of
delete, so you can reuse it next trip." Reusing it also means its items are all still ticked from
last time.

## Decision

- **`TaskList.archived`, a boolean, in the list's changelog.** It is list state, like the title,
  so it goes down the same changelog and every member's screen agrees. An archived list moves to
  a collapsed **Archived** section at the bottom of the lists screen, on every phone and browser
  on it. It is still synced, still opens, and can still be edited; it is only out of the way.
- **Only the owner archives or restores** — the same rule, in the same place
  (`domain/permissions.ts` `mayApply`), as delete (ADR 0006): a person invited onto a list does
  not get to move it off the screen of the person who made it. A list mutation carrying
  `archived` from a member is `forbidden`. A member who wants it off their own screen can leave,
  as before.
- **A boolean, not an `archivedAt` timestamp.** Restoring has to be *sent*, and the Android
  client's outbound JSON omits nulls (`DielysJson.outbound`, `explicitNulls = false`) so that a
  patch names only the fields it changes (F5.4). `archived: false` is a value; `archivedAt: null`
  would never leave the phone. It is ordinary per-field last-write-wins — not sticky like a
  tombstone — because archiving is meant to be undone.
- **Restoring asks whether to untick everything**, when there is anything ticked. Unticking is
  not a new operation: it is one `done: false` task patch per ticked item, queued in the outbox
  in the same transaction as the restore, exactly as if each had been unticked by hand. The other
  members see the items come back one by one, and an offline restore works like any other edit.
- **Additive (F2).** No `PROTOCOL_VERSION` bump. A list change stored before the field existed has
  no `archived`; the server fills in `false` when it sends one, so a client always gets a value.
  An older client ignores the field and shows an archived list among the others.

## Consequences

- A list can now be put away and brought back without retyping it, and starts its next use with
  nothing ticked.
- An archived list still syncs and still takes part in notifications, wake pushes and catch-up.
  It is hidden, not dormant. Making it dormant would mean a second state for the sync engine to
  get right, for lists that by definition hardly change.
- A member on an older app version keeps seeing an archived list among the active ones until
  they update. Nothing is lost either way.
- An older client that offered "Archive" to a member would get `forbidden`, but no older client
  offers it at all, so this is the rule ADR 0006's consequence describes, not a live path.
