# 0011 — The web client is local-first, like Android

**Status:** Accepted
**Date:** 2026-09-21
**Supersedes:** the "online-first, no local replica, no outbox" scope decision `web/` shipped
with (issue #74, `web/AGENTS.md` before this ADR).
**Builds on:** [docs/SYNC.md](../SYNC.md) and [F5](../CODE_STANDARD.md#standard-f5) — this ports
the model Android already uses; it does not change the wire protocol or the server.

## Context

`web/` shipped online-first to keep its first cut small: every tap `await`ed
`POST /lists/{id}/mutate` before the screen changed, and every page load re-fetched memberships
and replayed each list's changelog before anything was shown. A tab-lifetime memory cache
(`listCache.ts`) took the edge off revisiting a list, but not off the two things people actually
felt:

- **Every tap cost a round trip.** Ticking an item, adding one, starring, dragging — each one
  appeared a network round trip after it was made, and failed outright on a bad connection.
- **Every load started from nothing.** A reload spent the refresh token, then fetched
  memberships, then one full catch-up per list, before the lists screen could say anything.

Android never had either problem because of the model docs/SYNC.md describes: the UI reads local
storage, a tap is one local transaction plus an outbox row, and the network only has to make
devices agree eventually. The web client was frustratingly slow next to it.

## Decision

**`web/` adopts the same local-first model, ported file-for-file where it can be.**

- **Replica** (`web/src/data/replica.ts`) — every list, task and per-list cursor this browser
  can see, plus the outbox. The port of Android's Room tables and `ChangeApplier.kt`: one
  `apply()` for socket pushes, catch-up pages and mutation acks alike (F5.6), the cursor moved
  only in the same write as the change (F5.8), and the same "a queued edit shadows its own
  echo" rule.
- **IndexedDB** (`web/src/data/idb.ts`) — the replica's persistence, one IndexedDB transaction
  per replica write, so an entity and its outbox row land together (F5.7). No wrapper library
  (N1). Schema changes are numbered, non-destructive `onupgradeneeded` steps — the web's
  equivalent of G2's ban on destructive migrations, for the same reason: the outbox lives there.
- **Repository** (`web/src/data/repository.ts`) — the port of `DielysRepository.kt`. Every user
  action is a local commit and a drain request; nothing awaits the network.
- **Sync engine** (`web/src/data/syncEngine.ts`) — the port of `SyncEngine.kt`: drain the outbox
  oldest-first with each row's stored idempotency key (F5.2), fold in `/auth/memberships`
  (discovering new lists, forgetting ones this account left, #60), then catch up lists whose
  head moved. A 4xx other than 401/408/429 parks a row as dead rather than dropping it;
  anything else retries with backoff.
- **Session start** stays signed in on a stored refresh token and user id without waiting for
  the refresh, so a reload paints from IndexedDB immediately — offline included. Only a refresh
  the server actually rejects signs the browser out (#83's rule, unchanged).

Where the web differs from Android, and why:

- **No background worker.** There is no `WorkManager` on the web. The engine runs while a tab
  is open — on start, on every commit, on `focus`/`visibilitychange`/`online`, and on a backoff
  timer after a failure. A tab closed with rows still queued drains them on its next open,
  because they are in IndexedDB. H3.4's "survives process death" holds in that sense; it does
  not reach the server while no tab is open.
- **Several tabs share one database.** A `BroadcastChannel` ping after each write makes the
  other tabs reload, and a Web Lock keeps two tabs from draining at once. Two tabs racing the
  same row anyway is safe — the idempotency key makes the second send a duplicate ack.
- **No daily full sweep.** The first sync after a page load ignores the memberships' heads and
  pulls every list instead; a tab rarely lives a day.
- **Signing out erases the replica**, outbox included, the same as it erased the old memory
  cache. A shared browser must not show the next account the last one's lists; stored data for
  a different user id is also wiped on open, in case sign-out never ran.

## Consequences

- Taps show up in the frame they were made in (measured at ~5 ms from submit to row on screen,
  locally), and a reload shows the last known lists before any request answers.
- Edits made offline queue and send on reconnect instead of failing with an error. The
  "could not add / could not create" error paths in the pages are now effectively unreachable;
  they are left in place, harmless, rather than churned in this change.
- A row the server refuses for good is kept and counted (`Replica.stuckCount()`), matching
  Android. There is no web UI for that count yet — Android's "stuck" indicator is the model when
  one is added.
- `sync/listCache.ts`, `sync/hydrateList.ts` and `sync/applyChange.ts` are gone; the two page
  hooks (`useTaskBoard`, `useListsOverview`) keep their shapes and read the replica instead.
- Sharing (`useSharing.ts`) and account deletion stay live reads, deliberately: a stale answer
  about who can see a list is worse than a beat of "checking…", for the same reason Android
  never caches them either.

## Alternatives considered

- **Keep online-first and add optimistic UI on top.** Faster taps, but still a blank screen on
  every load, still nothing working offline, and a second, weaker consistency model to keep
  correct next to Android's. The whole point of `docs/SYNC.md` is that there is one.
- **A library (Dexie, RxDB, a CRDT sync engine).** N1: four object stores and one write shape
  do not earn a dependency, and a sync library would bring its own model in place of the
  changelog-and-cursor one the server already implements.
- **A service worker background sync.** Chromium-only (`SyncManager`), and it would need the
  access token outside the page. Revisit if "drains only while a tab is open" turns out to
  matter in practice.
