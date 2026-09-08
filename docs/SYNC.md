# How sync works

This is the hard part of Dielys. The code says *what*; this document says *why*, so the
reason a rule exists is still findable six months from now.

## The model: local-first, not request/response

The UI never talks to the network directly and never blocks on it. Every screen reads from
local Room tables via `Flow`. Every user action — add a task, tick it, star a list — is a
single local transaction: write the change to Room, and write an **outbox row** describing the
same change, atomically, in the same transaction ([F5.7](CODE_STANDARD.md#standard-f5)).

This is why the app feels instant even on a bad connection: it *is* instant, because nothing
the user does waits on a network round trip. The network's job is to eventually make every
device agree, not to gate what one device shows its own user right now.

## The outbox

A `WorkManager` job drains the outbox continuously, retrying forever with backoff, surviving
process death because `WorkManager` guarantees are exactly that
([H3.4](CODE_STANDARD.md#standard-h3)). Each outbox row carries an **idempotency key**
([F5.2](CODE_STANDARD.md#standard-f5)). If the drain sends a mutation, the server applies it,
and the response is lost before the client sees it (dead socket, app killed, phone locked in a
pocket), the retry carries the same key. The server recognizes it and returns the original
result instead of applying the change twice. This is the entire answer to "what if the same
tap gets sent twice" — no dedup logic anywhere else in the stack.

## The changelog and the sequence number

Every accepted mutation becomes a row in the list's changelog, with a **sequence number
assigned inside the `ListRoom` Durable Object**, in the same transaction as the state write.
Because a DO is single-threaded for its own storage, "assign the next number" and "commit the
change" can never race with another client's mutation to the same list — there is no
distributed counter to get wrong. This is the one property the whole design leans on; it is
why the backend had to be something with per-entity single-threading, not a stateless function
in front of a shared database (see [ADR 0001](adr/0001-workers-and-durable-objects.md)).

## The cursor

Each client holds a **cursor**: the last sequence number it has applied, per list. Two
delivery paths exist:

- **WebSocket** pushes new changes as they happen — the fast path, used when the app is open
  and connected.
- **`GET ?since=N`** pulls everything after the cursor — the catch-up path, used on
  reconnect, on a detected sequence gap, or after an FCM wake.

Both paths apply through the *same* code that turns a changelog row into a local Room write.
This is deliberate: correctness comes from the cursor and the gap check
([F5.6](CODE_STANDARD.md#standard-f5)), not from the WebSocket. The socket is purely a latency
optimization — if it silently died mid-session ([H3.11](CODE_STANDARD.md#standard-h3)), the
worst case is a slower catch-up once a heartbeat notices, never a missed change, because the
next successful message of any kind carries a sequence number the client can check against its
cursor.

**The cursor advances only after the change is committed locally**
([F5.8](CODE_STANDARD.md#standard-f5)) — never on receipt, before the write lands. A crash
between "received" and "written" must replay the same change on next launch, not skip it.

## Conflicts

Two devices can edit the same task while both are offline. Resolution is
**per-field last-write-wins, decided on the server's timestamp, tie-broken by device id**
([F5.4](CODE_STANDARD.md#standard-f5)). Not the client's clock — a phone's clock can be
wrong by minutes and is never trusted for ordering ([F5.9](CODE_STANDARD.md#standard-f5));
[H3.8](CODE_STANDARD.md#standard-h3) exists specifically to pin this down. Per-field, not
per-row, so if one device renames a task while the other ticks it, both edits survive instead
of one clobbering the other.

Deletes are **tombstones** (`deleted_at`), never a real `DELETE`
([F5.3](CODE_STANDARD.md#standard-f5)). A delete racing an update
([H3.7](CODE_STANDARD.md#standard-h3)) must have a defined winner — the tombstone — rather than
depend on which write happened to land in the database last. A hard-deleted row leaves nothing
to compare timestamps against; a tombstone does.

## Identity and ordering

Entity ids are **client-generated UUIDv7** ([F5.1](CODE_STANDARD.md#standard-f5)) — the server
never mints one. This means a device can create a task while fully offline and know its final
id immediately, with no reconciliation step when it later syncs, and two devices creating
tasks offline at the same time ([H3.6](CODE_STANDARD.md#standard-h3)) never collide.

List ordering uses **fractional indexing**
([F5.5](CODE_STANDARD.md#standard-f5)): positions are strings, not integers, so inserting or
reordering never renumbers every sibling row. Two devices reordering the same list offline
([H3.9](CODE_STANDARD.md#standard-h3)) merge their fractional positions on reconnect without a
global renumber pass that would itself need to be conflict-resolved.

## Why this is worth nine invariants and twelve tests

Every rule above maps to one line in [F5](CODE_STANDARD.md#standard-f5) and one scenario in
[H3](CODE_STANDARD.md#standard-h3). The failure mode of getting any of them wrong is specific
and cruel: it does not show up in development, where the network is fast and one person is
testing. It shows up on the other person's phone, in a shop, with no signal, and by the time
anyone notices the cart has two of the same item, the state that would explain why is gone.
That is what the invariants and the scenario matrix exist to catch before it ships, not after.
