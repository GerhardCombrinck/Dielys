# Dielys wire protocol

`PROTOCOL_VERSION = 2` (`src/version.ts`). Versions still served:
`SUPPORTED_PROTOCOL_VERSIONS = [2]`.

This document, `src/types.ts`, and `fixtures/` are one unit — a change to any one of them
without the other two is incomplete (CODE_STANDARD.md F1, F4).

## Entities

- **`Task`** — id (UUIDv7), listId, title, done, starred, position (fractional index string),
  deletedAt (tombstone), updatedAt (server timestamp).
- **`TaskList`** — id (UUIDv7), title, backgroundPhotoUrl, deletedAt, updatedAt.

Bounds enforced at the boundary (F3): `MAX_TITLE_LENGTH` 1000, `MAX_POSITION_LENGTH` 256,
`MAX_URL_LENGTH` 2048, `MAX_ID_LENGTH` 64. A message that breaches one is rejected with
`code: "malformed"`, never truncated — silently shortening a user's text is worse than
refusing it.

## Changelog

Every accepted mutation to a list becomes a `ChangeEnvelope`, with a `seq` assigned inside the
list's `ListRoom` Durable Object. Clients apply changes in seq order and hold a per-list
cursor. See `docs/SYNC.md`.

`ChangeEnvelope` is a discriminated union on `entityType: "task" | "list"`. The discriminator
is explicit rather than inferred from the shape of `entity`, so that adding a field to either
entity can never start misclassifying old rows.

## Mutations

A `Mutation` names **only the fields it intends to change**, in `patch`. This is what makes
per-field last-write-wins (F5.4) work: two devices editing different fields of the same task
while offline send disjoint patches, and both survive. Sending whole entities would make every
edit clobber every field.

- An absent key in `patch` means "leave alone".
- A `patch` naming an `entityId` the list has never seen is a **create**. A task create MUST
  carry `title` and `position`; a list create MUST carry `title`. Anything less is rejected
  with `incomplete-create` — the server does not invent defaults for fields only the client
  can know.
- `entityId` is always client-generated (F5.1). The server never mints one.
- **Tombstones are sticky** (F5.3). Once `deletedAt` is set, a later patch may still update
  other fields, but `deletedAt: null` is ignored — an update never resurrects a deleted row.
  This is the defined winner H3.7 requires; plain LWW would let arrival order decide.

## Messages

| Direction | `type` | Purpose |
|---|---|---|
| C → S | `hello` | Opens a session: protocol version, list, cursor, device |
| C → S | `mutate` | One mutation, carrying its idempotency key |
| C → S | `catch-up` | Pull everything after a cursor (same as `GET ?since=N`) |
| S → C | `hello-ok` | Session accepted; carries `maxSeq` so the client sees at once that it is behind |
| S → C | `hello-error` | `unsupported-protocol-version`, `unauthorized`, or `malformed` |
| S → C | `change` | A change pushed as it happens — the latency path |
| S → C | `ack` | Result of a mutation; `duplicate: true` when the key was already applied |
| S → C | `catch-up-response` | A page of changes; `truncated` when more remain |
| S → C | `error` | A rejected message, with a stable `code` and no internal detail |

## Handshake

1. Client opens a WebSocket, sends `ClientHello` with its `protocolVersion`, `listId`,
   `cursor`, and `deviceId`.
2. Server replies `ServerHelloOk`, or `ServerHelloError` with `code:
   "unsupported-protocol-version"` if the client is on a version the server no longer serves,
   or `"unauthorized"` if the Worker's membership check (CODE_STANDARD.md L3) fails for this
   device's user.
3. On a detected seq gap, the client sends `CatchUpRequest` (equivalent to `GET ?since=N` over
   HTTP — both paths apply through identical code, F5.6).

Liveness uses the Hibernation API's automatic ping/pong: the client sends `ping` and the
runtime answers `pong` without waking the DO. A missing pong is how H3.11 (socket dies
silently) gets detected; recovery is a reconnect plus a catch-up pull, never a resync from
scratch.

## Transport

The WebSocket is an optimization. The **HTTP path is the primary write path**, because the
Android outbox drains from `WorkManager` with no socket open:

- `POST /lists/{listId}/mutate` — body is one `Mutation`, response is a `MutationAck`.
- `GET /lists/{listId}/changes?since=N` — response is a `CatchUpResponse`.
- `GET /lists/{listId}/ws` — WebSocket upgrade.

## Authentication

Types in `src/auth.ts`. Full rationale in `docs/adr/0002-authentication.md`; the enforceable
rules are CODE_STANDARD.md L1–L3.

| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST | `/auth/login` | none | `LoginRequest` → `TokenPair` |
| POST | `/auth/refresh` | none | `RefreshRequest` → `TokenPair`, rotating the refresh token |
| GET | `/auth/memberships` | access token | Lists the caller can reach, in the caller's own order |
| POST | `/auth/memberships/position` | access token | `SetListPositionRequest` → `SetListPositionResponse` |
| POST | `/lists/{listId}` | access token | Claim a client-generated list id as owner |
| POST | `/lists/{listId}/invite` | access token, owner | `CreateInviteRequest` → `CreateInviteResponse` |
| POST | `/invites/accept` | access token | `AcceptInviteRequest` → `AcceptInviteResponse` |
| POST | `/admin/users` | `ADMIN_TOKEN` | Account creation (L2). Not a public endpoint |

Every request carries the access token as `Authorization: Bearer <jwt>` — **including the
WebSocket upgrade**. The token is on the upgrade request rather than in the `hello` message
because L3 requires the *Worker* to authorize before anything reaches a `ListRoom`, and the
Worker cannot see messages sent after the socket is established. OkHttp sets headers on an
upgrade, so the Android client can do this; a browser could not, which is a problem `web/`
will have to solve when it exists.

Errors carry a stable `code` (`AuthErrorCode`) and never a stack or an internal message (D4).
`invalid-credentials` covers a wrong password, a wrong email, and an account that does not
exist, deliberately — three distinct codes would be an account-enumeration oracle.

### Creating and sharing a list

List ids are client-generated (F5.1), so the server cannot grant ownership at creation time.
Instead the client picks a UUIDv7 and **claims** it: the first caller to claim an id nobody
holds becomes its owner. Claiming a list you already belong to is a no-op, so an outbox retry
is harmless; claiming one somebody else holds is refused.

The owner shares a list by typing the recipient's email address; the server mints an invite
scoped to that address (`email` on the invite's JWT claims) and **mails the link itself** —
the owner's own device never sees the bearer token. Accepting is still a POST to
`/invites/accept` with just the token, but `UsersRoom` now checks the accepting account's own
email against the one the invite was minted for and refuses (`forbidden`) on a mismatch: an
invite can only be finished by the address it was addressed to, not by whoever ends up holding
the link.

A request for a list the caller is not a member of returns **403, not 404**: membership must
not double as an oracle for which list ids exist.

### Ordering the lists

The order the lists appear in belongs to the **member**, not the list: `Membership.position`
is a fractional index (F5.5) stored per `(user, list)` in `UsersRoom`, so two people sharing a
list each drag their own copy around without touching the other's screen. It is not part of a
list's changelog for the same reason — a `ListRoom` change is seen by everybody on that list.

`POST /auth/memberships/position` sets one membership's key, computed client-side from the two
neighbours it was dropped between. A membership with a null position sorts after every
positioned one, by age, so a newly joined list lands at the bottom rather than in the middle.

## Wake push (M1)

Types in `src/push.ts`. The enforceable rule is
[M1](../docs/CODE_STANDARD.md#standard-m1), and it is a `(SYNC)` one.

A push is a **hint to sync**, not a message and not a transport. The server sends it as an FCM
**data** message — never a `notification` payload — and the payload is exactly three keys:

```json
{ "type": "sync", "listId": "01936b2a-4c3d-7e8f-9a0b-1c2d3e4f5a6b", "seq": "7" }
```

No task title, no list name, no note body, ever. Google's servers are as much "outside this
system" as a log aggregator is, so the same rule applies as to logging (D4). On receipt the
client runs a normal sync and reads Room; anything it puts on screen is composed from local
data afterwards, never from the payload.

`seq` is a **decimal string**. FCM's `data` field is `map<string, string>` at the API level,
so a JSON number cannot travel in it. That is an encoding constraint on one field, not a
relaxation of M1 — the key set is unchanged, and `fixtures/push/sync-wake.json` pins both.

The client does not act on `listId` or `seq` beyond logging them: a wake drains the outbox and
catches up **every** list, so the two fields describe what happened rather than deciding what
to do. That is also why every pending wake collapses to one on FCM's side.

Delivery is best-effort by design. A dropped push means a list is late, not wrong — the
half-hourly `WorkManager` sync is the floor under it (H3.12).

| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST | `/devices/token` | access token | `RegisterDeviceRequest` — store this device's FCM token |

The device the token is filed under comes from the access token's `deviceId` claim, not from
the body: a client must not be able to register a push token against somebody else's device.

## Positions (F5.5)

`Task.position` is an opaque string to the server — it stores and returns it, and never
computes one. Ordering is **client-side**, ascending by `(position, id)` under **plain byte
comparison**. Never `localeCompare`: it would put `"a"` before `"B"` and silently reorder the
list.

The scheme is an order key: a base-62 integer part whose first character encodes its own
length, plus an optional fractional part. Appending increments the integer, so the first 62
items are two characters (`a0`..`az`), the next 3,844 are three (`b00`..`bzz`). Inserting
between two items is the only operation that lengthens a key. `a`..`z` are positive integer
parts, `A`..`Z` negative — prepending walks down through `Zz`, `Zy`, and so on.

The reference implementation is `server/src/domain/position.ts`; the Kotlin client mirrors it.
`fixtures/positions.json` is what makes a mismatch fail a build rather than fail in a shop.

Two devices inserting at the same spot while offline **can generate the same key**. Nothing
prevents that — there is no coordination while offline. That is why the sort is `(position,
id)`: the UUIDv7 breaks the tie identically on both devices, both rows survive, and neither is
a duplicate ([H3.9](../docs/CODE_STANDARD.md#standard-h3)).

## Versioning rules (F2)

- Additive changes (new optional field, new message type) do not bump `PROTOCOL_VERSION`.
- Breaking changes (removed field, changed meaning, changed type) bump it, and the PR states
  the rollout plan.
- Unknown fields on a received message are ignored, never rejected.

### Version 1 → 2

Breaking: `ChangeEnvelope` gained a required `entityType`. Rollout plan: none needed — no
client has ever spoken version 1. The Android app has no sync code yet and the Worker rejected
every request with 501 until this change, so there is no deployed reader of the old shape.
Version 1 is not in `SUPPORTED_PROTOCOL_VERSIONS`; a client claiming it is told
`unsupported-protocol-version`.

## Fixtures

`fixtures/changes/` holds `ChangeEnvelope` payloads, including the edge cases F4 requires:
tombstoned task, unicode title, maximum-length title (exactly at the bound), and a conflicting
update pair that ties on `serverTimestamp` so the device-id tiebreak is pinned down.
`fixtures/messages/` holds one whole wire message per `type`, including an empty
`catch-up-response`. `fixtures/push/` holds the wake payload, whose test asserts the *complete*
key set rather than a subset — that assertion is how M1 fails a build instead of leaking.
`fixtures/positions.json` holds the fractional-indexing vectors.

Both the TypeScript and Kotlin test suites parse every fixture and assert a byte-identical
round trip. Fixtures are hand-written, never generated by the code under test.
