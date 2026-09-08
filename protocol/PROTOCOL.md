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
| GET | `/auth/memberships` | access token | Lists the caller can reach |
| POST | `/lists/{listId}` | access token | Claim a client-generated list id as owner |
| POST | `/lists/{listId}/invite` | access token, owner | → `CreateInviteResponse` |
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
is harmless; claiming one somebody else holds is refused. The owner then mints an invite, and
the invitee — already logged in as themselves — POSTs it to `/invites/accept`.

A request for a list the caller is not a member of returns **403, not 404**: membership must
not double as an oracle for which list ids exist.

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
`catch-up-response`.

Both the TypeScript and Kotlin test suites parse every fixture and assert a byte-identical
round trip. Fixtures are hand-written, never generated by the code under test.
