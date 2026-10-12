# 0015 — Moving an item to another list is a new task there and a tombstone here

**Status:** Accepted
**Date:** 2026-10-12
**Builds on:** F5.1 (client-generated ids), F5.3 (sticky tombstones), F5.7 (one transaction for
the local write and its outbox rows). No protocol change.

## Context

An item ends up on the wrong list: light bulbs on Groceries when they belong on Hardware. Until
now the only fix was to delete it and type it again on the other list.

A task belongs to exactly one list's changelog, and each list is its own `ListRoom` Durable
Object. Nothing on the server can change a task's `listId` in one write: the source room would
have to give it up and the target room take it, and those are two rooms that do not share a
transaction.

## Decision

- **A move is two ordinary task mutations, made on the device.** A create on the target list
  (title, position, and `starred` if it has a star) and a tombstone (`deletedAt`) on the source.
  Both are written to the local store and queued in the outbox in **one** transaction (F5.7), so
  the person moving it never sees the item on both lists or on neither, online or offline.
  Android `DielysRepository.moveTaskToList`, web `Repository.moveTaskToList`.
- **The copy gets a new id.** The old id is tombstoned, and a tombstone is sticky (F5.3), so
  reusing it on another list would put the same id live in one room and dead in another, with
  every client keyed on it to get confused. A fresh UUIDv7 avoids that entirely.
- **It keeps its title and star, not its tick.** "Move to…" is only offered on items still to
  do. It lands where a new item would (the device's top/bottom setting), except that a starred
  one goes to the very top, where starring it would have put it.
- **Only lists still in use are offered.** Archived lists (ADR 0014) are left out, because moving
  something onto a list that is put away would hide it.
- **No protocol change, no server change.** Both halves are mutations every client already sends.
  The membership check the Worker makes on every write covers the target list as it would for an
  ordinary add.

## Consequences

- The two halves reach the server separately. If one is refused for good (for example, the
  person was removed from the target list while offline), the other still lands. The item is then
  either gone from the source without arriving, or on both lists. The refused half shows up as a
  stuck edit, like any other refused write. This is rare, visible, and nothing is silently lost:
  the tombstoned task is still in the source's changelog.
- Other people on the two lists see an ordinary add on one and an ordinary delete on the other,
  and the notifications for those (ADR 0012) say exactly that. There is no "moved" event.
- An item's history does not follow it: on the target list it starts as a new item.
