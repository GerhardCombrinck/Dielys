# 0012 — Notifications for a shared list, composed on the phone

**Status:** Accepted
**Date:** 2026-09-24
**Builds on:** [ADR 0003](0003-fcm-wake-push.md) (the wake push and where its fan-out lives),
[M1/M2](../CODE_STANDARD.md#standard-m1), [ADR 0010](0010-background-sync-setting.md) (a per-user
value held server-side, last-write-wins, no idempotency key).

## Context

Somebody on a shared list wants to know when the other person adds, ticks off, deletes or
changes something — per list, only on shared lists, and only for the kinds of change they pick.

The pieces that make a phone hear about a change already exist: every accepted write wakes each
member device without a socket (M2), and the phone syncs. What was missing is anything a person
can see. M1 already says how that must work: "any user-visible notification MUST be composed
client-side, after sync, from local Room data — never from the push payload". This ADR is how,
and the three things the server had to learn to make it possible.

## Decision

### The choice is per member, per list, held in `UsersRoom`

`memberships.notify_events` (users migration 0012) holds a set of `NotifyEvent`s —
`added`, `checked`, `deleted`, `updated` — empty by default, including for a membership made by
accepting an invite. `Membership.notify` carries it on `/auth/memberships`;
`POST /auth/memberships/notify` replaces it whole. Same shape as `Membership.position`, for the
same reasons: the two people on a list choose independently, it is nothing the list's changelog
should broadcast, and a retried identical body is the same state, so no idempotency key.

Per member rather than per device: turning it on from the phone or from `web/` means the same
thing on every phone signed in to that account. `web/` can set it but never shows a notification
(it has no service worker), and says so in the dialog.

The server does not refuse the choice on a list that is not shared. A list stops being shared
when the other person leaves, and starts again when they are re-invited; keeping the set through
both is what the person would expect. The clients hide the control on a solo list, and a solo
list has nobody else to be notified about in any case.

### Every change names its author

`ChangeEnvelope.authorUserId` (list migration 0003, `changes.user_id`) is the account the Worker
authenticated for the write. The Worker sets it as a query parameter on the request it forwards
to the `ListRoom` with `URLSearchParams.set`, which replaces anything a client put there. The
room records it with the change, and a socket carries it from its upgrade, the same way `role`
does (ADR 0006).

Without it, a phone can recognise its own device's edits (`deviceId`) but not its own account's
edits made from the web or a second phone, and would notify you about yourself. Changes written
before the migration have `null`, and every reader treats `null` as an unknown author, never as
"somebody else". Nothing is backfilled.

Additive under F2: no protocol version bump. The field is also what a line in the notification
names ("anna added Milk"). The name is the part of the member's email before the `@`, read from
`GET /lists/{id}/members`. That is the only name the system has for anybody.

### The phone decides, inside the apply transaction

`ChangeApplier` reports every applied task change to `ListActivityRecorder` with the task as it
was *before* the change. The pure `classifyTaskChange` names it: a task not seen before is
`added`, a tombstone is `deleted`, a `done` flip either way is `checked`, and a title or star
change is `updated`. A move is nothing. So is anything done to an already-deleted task, or a task
created and deleted before this phone saw it. If the list is shared, subscribed to that kind, the
author is known and is not this account, and the app is not on screen, a row goes into
`list_activity`. The row is written in the same transaction as the change and its cursor, so a
crash loses or replays both together (F5.8).

That path is taken however the change arrived: catch-up, the drain's echo, or a socket. The
socket only runs in the foreground, where nothing is recorded anyway.

A list's **first pull** is history, not news. `SyncEngine.catchUp` from cursor 0 applies every
change up to the head its first page reports as quiet, across every page. A new phone with a
subscription already on the server, or a list just joined, does not dump its whole history onto
the lock screen.

At the end of every `SyncWorker` run, `ListNotifier` turns the unposted rows into one
notification per list: the list id is the tag, so later changes update it in place. It uses
InboxStyle, newest first. Lines are read against the task as it is *now*, so something ticked and
unticked again says "unticked". Tapping it opens the list. Swiping it away, or opening the list,
clears its rows. A row is upserted on (list, task, kind), so a task renamed three times is one
line with its latest name.

### Wake priority follows the choice

`planWake` (`server/src/domain/wake.ts`) sends FCM `high` only to a device whose account has a
non-empty `notify` on the list and did not make the write. Every other device gets `normal`.
Before this, every wake was `high`. Android demotes an app whose high-priority messages do not
end in a visible notification, which a pure sync wake never does. The payload is unchanged: still
exactly `{type, listId, seq}`, with no content (M1). Who is woken is unchanged too. The choice
decides urgency, never whether a phone syncs.

On the phone, a wake now enqueues **expedited** work (`requestUrgentSync`). It falls back to
ordinary work once the quota is spent. A high-priority push is what gives a dozing phone the
window to run it. Before Android 12, expedited work runs as a foreground service, which is why
`SyncWorker` has a quiet `getForegroundInfo`.

## Consequences

- Nothing reaches a phone without `FCM_SERVICE_ACCOUNT_JSON` on that deployment. Until then a
  backgrounded phone only hears about changes on the periodic floor (H3.12), and a notification
  can be up to that interval late. `dielys-prod` does not have it yet (PLAN.md), so this feature
  is effectively off in production until the key is set there.
- Android 13+ asks for `POST_NOTIFICATIONS` when somebody first turns a list's notifications on,
  not at start-up. Refused, the dialog says so. Rows recorded while posting is not allowed are
  dropped rather than saved up for later.
- Names are fetched from the server at post time, once per list per process, and fall back to
  "Someone". A member who has since left is still "Someone".
- The `NOTIFY` outbox row's entity id is `notify:{listId}`, not the list id. The applier treats a
  queued row for an entity as a newer local edit and skips writing that entity's incoming
  changes, and a notification choice is not an edit to the list.
- A queued choice is not overwritten by a memberships answer that has not seen it yet, on both
  clients, the same rule a queued drag already had.
- Notification strings are translated into all ten other languages. The non-Afrikaans ones are
  a best effort and want a native speaker's read, like the rest of those files.

## Alternatives considered

- **Server-composed FCM `notification` payloads.** Simplest, and no sync needed before
  showing. Rejected by M1: it puts task titles on Google's servers, and the text could say
  something Room disagrees with.
- **FCM topics per list.** Rejected by M2 for the same reason as before. Membership is already
  the fan-out set, and a second subscription list would drift from it.
- **A device-local setting.** Simpler, but a choice made on one phone would not apply to the
  other or be settable from `web/`. ADR 0010 already rejected the same shape for the sync
  setting.
- **Author as email on the change.** It would name people without a members call. But it would
  also copy personal data into every list's changelog, where account deletion (ADR 0007) does
  not reach. A user id is opaque, and it is already visible to the list's members.
- **Web Push.** A service worker, VAPID keys, and its own subscription table. A project of its
  own, left for later. The choice made in `web/` today already applies to the phones.
