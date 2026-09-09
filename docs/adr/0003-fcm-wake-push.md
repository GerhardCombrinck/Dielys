# 0003 — FCM wake push: `seq` on the wire, and where the fan-out lives

**Status:** Accepted
**Date:** 2026-09-09

## Context

[M1](../CODE_STANDARD.md#standard-m1) is tagged **(SYNC)**, so it cannot be relaxed without
an ADR. It specifies the wake payload literally:

> Payload is limited to `{ "type": "sync", "listId": "<id>", "seq": <n> }`.

Implementing it against FCM HTTP v1 surfaced two things the standard could not have known
when it was written: the API will not carry that payload as written, and the two rules that
describe where the fan-out happens ([M2](../CODE_STANDARD.md#standard-m2) and
[L3](../CODE_STANDARD.md#standard-l3)) point in different directions.

## Decision

### `seq` travels as a decimal string

FCM's `message.data` is `map<string, string>` at the API level. There is no JSON number to
put `<n>` in; a message with a numeric value is rejected before it leaves Google's front
door. So the payload on the wire is:

```json
{ "type": "sync", "listId": "01936b2a-4c3d-7e8f-9a0b-1c2d3e4f5a6b", "seq": "7" }
```

Everything M1 actually protects is unchanged. The key set is still exactly those three; there
is still no `notification` payload; there is still no task title, list name or note body
anywhere in it; the client still treats it as a hint and reads Room afterwards. What changed
is the encoding of one field, forced by the transport — not the contract.

This is recorded rather than silently done because M1 is **(SYNC)** and the text says
`<n>`. A reader comparing the standard to the wire should find the answer here rather than
assume the rule drifted.

`protocol/src/push.ts` is the single declaration of the payload, and
`protocol/fixtures/push/sync-wake.json` is asserted against by both suites: the TypeScript
fixture test checks the key set and that `seq` parses as a safe integer, and the Kotlin
`PushHandlerTest` parses the same bytes.

### The fan-out is `UsersRoom`'s, not `ListRoom`'s

M2 says "on a `ListRoom` write, the DO determines which member devices (via `UsersRoom`
membership) do not have an active hibernating WebSocket". L3 says a `ListRoom` never checks
membership. Read literally, M2 has a `ListRoom` asking who the members are, which is the
thing L3 exists to prevent — not for authorization here, but the query is the same query, and
a helper that exists is a helper that eventually gets called from the wrong place.

`ListRoom` therefore contributes only what it alone knows — the device ids currently holding
a socket, plus the device that made the write — and calls
`UsersRoom.notifyListMembers(listId, seq, informed)`. `UsersRoom` owns the membership join,
the exclusion, the send and the dead-token cleanup.

This keeps L3 intact by construction: `ListRoom` never learns a list's membership, and the
call it makes happens strictly *after* the write is accepted, so it can never refuse
anything. It also puts the OAuth access-token cache in the one singleton DO rather than in
every `ListRoom`, and lets a token FCM reports as gone be deleted where the row already is,
with no second hop.

The M2 rule it implements is unchanged: a device with an active socket is not also sent a
push for the same change.

### The device row is its own table, not a column on the refresh token

M2 says the token is stored "keyed by `deviceId`, alongside the refresh token row for that
device". It is keyed by `device_id`, in its own `devices` table. Refresh tokens rotate on
every use ([ADR 0002](0002-authentication.md)); an FCM token does not, and hanging it off a
row that is deleted and rewritten on every refresh would mean either losing it or carrying it
forward through a code path whose whole job is to invalidate things.

Keying by `device_id` rather than by `(user_id, device_id)` is deliberate: a phone handed to
the other member of the household re-points its row on the next sign-in, instead of leaving a
second registration that wakes it for the previous account's lists.

### An unset credential is not fail-closed

`JWT_SIGNING_KEY` fails closed — an unset one means the Worker serves nothing but `/health`,
because the alternative is signing tokens with the literal string `"undefined"`
(see the [README](../../README.md)).

`FCM_SERVICE_ACCOUNT_JSON` is the opposite. Absent, `parseServiceAccount` returns `null`,
`notifyListMembers` returns immediately, and the mutation commits and acks exactly as it
would have. The consequence of no push is that a backgrounded phone hears about a change on
its next WebSocket connect or on the half-hourly `WorkManager` sync
([H3.12](../CODE_STANDARD.md#standard-h3)) — later, never wrong. Failing a write because a
best-effort wake could not be sent would take a degraded path and make it an outage.

The same reasoning runs on the client: without `android/app/google-services.json` there is no
default `FirebaseApp`, `PushTokens.refresh()` returns without asking for a token, and the app
works on the socket and the floor. The Google Services Gradle plugin is applied only when the
file exists, because it hard-fails at configuration time without it and the file is
gitignored — otherwise nobody could build or test without a Firebase project of their own.

## Consequences

- The client parses `seq` with `toLongOrNull()` and ignores any payload that fails, so a
  malformed or foreign message costs nothing.
- One collapse key (`dielys-sync`) for every list, not one per list. A wake carries no
  instruction — the client drains its outbox and catches up every list regardless of what the
  payload named — so a second queued wake adds nothing, and FCM caps a device at four
  distinct collapse keys before evicting arbitrarily.
- A device row is deleted only on FCM's `404` (UNREGISTERED). A `400` can be our own
  malformed request, and deleting a good token because of our bug would silently stop waking
  a phone that is working fine.
- Registration rides `SyncEngine.sync()` as its last step rather than being sent from
  `onNewToken`, so it inherits `WorkManager`'s backoff and the half-hourly floor instead of
  needing a retry path of its own. A token that arrives in a dead spot is sent later, not
  lost.
- There is no unregister-on-sign-out call. It would need an authenticated request from
  `SessionRepository`, which creates a Dagger cycle
  (`SessionRepository` ← `AccessTokens` ← `HttpSyncApi` ← `SyncApi`). Instead `clearSession()`
  clears `pushTokenSent` so the next sign-in re-registers, and the server upserts by
  `device_id` so the row moves to the new account. The residual cost is one wasted wake at a
  phone that signs out and never signs back in, which 401s.
- `MAX_WAKE_TARGETS` is 32. The household has two phones; the cap is there so a membership
  bug cannot turn one write into an unbounded fan-out.
