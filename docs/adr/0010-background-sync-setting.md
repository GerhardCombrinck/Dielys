# 0010 — The background sync setting is held server-side, not on the phone

**Status:** Accepted
**Date:** 2026-09-17
**Builds on:** [ADR 0002](0002-authentication.md) (per-user state lives in `UsersRoom`),
[F3 and F5.2](../CODE_STANDARD.md#standard-f3). Follows `Membership.position`'s precedent for a
value that is last-write-wins and does not need an idempotency key.

## Context

Android's `WorkManager` runs a periodic catch-up sync as the floor under the socket and push —
both are best-effort, this is the guarantee that a change shows up eventually even when both
miss (H3.12). Whether that floor runs at all, and how often, used to be a purely local
`SharedPreferences` value: nothing else ever needed to see it.

The web app has no background worker (web/AGENTS.md) and cannot act on this setting itself. But a
person who wants to loosen it — stretch the interval to save battery, say, or turn it off
entirely on a phone they check constantly by hand — should not have to be holding that phone to
do it. The setting needed a home reachable from both clients.

## Decision

**The value lives in `UsersRoom`, one row per user, read and written over HTTP exactly like
`Membership.position` — not synced through a list's changelog, and not given an idempotency key.**

- `GET /auth/sync-settings` → `SyncSettings { enabled, intervalMinutes }`.
  `PATCH /auth/sync-settings` takes a `SyncSettingsPatch` (either field optional) and applies only
  what is present, the same partial-patch shape `TaskPatch` uses.
- No `seq`, no changelog entry, no idempotency key. Like `SetListPositionRequest`, this is a
  single-owner scalar — only the account holder ever writes it, from whichever device — so a
  retried identical `PATCH` lands on the same state either way and F5.2's replay protection has
  nothing to protect against.
- `intervalMinutes` is bounded to `[MIN_SYNC_INTERVAL_MINUTES, MAX_SYNC_INTERVAL_MINUTES]` (15
  minutes — `PeriodicWorkRequest`'s own floor — to one week) at the boundary (F3). A value outside
  it is `malformed`, not clamped: silently correcting it would let a client believe it set one
  value while the server scheduled another.
- `web/` only reads and writes the setting; it never schedules anything from it. Its Settings page
  says so implicitly by living under a "Mobile background sync" heading rather than claiming to
  control something happening in that tab.

### Android's local prefs become a synced mirror, not the source of truth

Before this, `UiPrefs`'s `SyncPrefs` was the only copy. It now also tracks
`lastSyncedEnabled`/`lastSyncedIntervalMinutes` — the `{enabled, intervalMinutes}` this device last
confirmed with the server — separately from the live values `WorkManager` schedules from. A new
`SyncEngine.syncSyncSettings()` step, run last in `sync()`, compares live against last-synced to
decide direction:

- **Live differs from last-synced** (the person touched the toggle or picker on this phone since
  the last successful sync): push it — `PATCH` with the live values — then record the result as
  the new last-synced baseline.
- **Live matches last-synced, or there is no baseline yet** (fresh install, or an existing one from
  before this setting synced at all): pull — `GET` — and adopt whatever the server has, so a value
  set from `web/` reaches the phone on its next sync without waiting for the phone's own UI to be
  touched.

This is the same problem a two-way LWW sync always has — "did the local copy change, or did we
just not hear about a remote change yet" — solved with a baseline snapshot instead of a vector
clock or a server-side `updatedAt`, because a single scalar per user does not need either: the
baseline itself only has to answer one yes/no question per sync.

`WorkManagerSyncScheduler.init`'s existing collector on `syncEnabled`/`syncIntervalMinutes` still
reschedules `WorkManager` on every value the sync step just accepted, whichever direction it came
from — a value pulled from the server reschedules exactly like one the person set by hand.

## Consequences

- One new table column pair on `users` (migration 0011), one new route, no protocol version bump
  (additive, like ADR 0009's ticket route).
- A toggle flipped on the phone takes effect on that phone immediately (`WorkManager` reschedules
  off the live value, same as before this ADR) and reaches the server, and other devices, on the
  next sync — not instantly. Good enough for a preference nothing time-critical depends on.
- A phone that never syncs (accepted permanently, not just offline) keeps whatever it already had
  scheduled locally. It only ever finds out about a change made elsewhere once it talks to the
  server again, same as every other synced value on that phone.
- `web/`'s copy is read fresh on every page load (there is no cache for this one value) and saved
  optimistically, matching the rest of `web/`'s online-first shape (web/AGENTS.md) rather than the
  cached-list pattern `listCache.ts` uses for list data.

## Alternatives considered

- **Keep it phone-local, add a "set on other devices" push message instead.** Would need a new
  message type, delivered over the socket or FCM, to a device that might not be reachable right
  now — solving a problem (cross-device write) that a plain row in `UsersRoom` already solves for
  free. Rejected as needless complexity for a value with no urgency.
- **Store it in `Membership`/list state instead of `UsersRoom`.** Wrong shape: this setting has no
  relationship to any one list, and every list a person is on would need to agree on it, which
  they should not.
- **Expand Android's outbox (`OutboxEntity`) to carry this.** `OutboxEntity` is keyed by `listId`;
  sync settings has none. Would mean giving the outbox a nullable list id and a second meaning for
  every consumer of it, for one scalar. The dedicated `syncSyncSettings()` step costs far less.
- **Always push, never pull (client is always right).** Simpler, but a periodic tick from a phone
  that has not been touched in weeks would silently re-assert its stale local value over whatever
  was set from `web/` in the meantime. Rejected — this is exactly the clobber the baseline
  comparison exists to avoid.
