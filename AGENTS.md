# AGENTS.md

Repo shape and rules an AI assistant (or a new human contributor) most often gets wrong here.
Full detail lives in `docs/CODE_STANDARD.md` — this is the summary that matters before you
write a line of code.

## Shape

One repo, two runtimes, one contract:

- `server/` — Cloudflare Workers + Durable Objects, TypeScript. One `ListRoom` DO per list,
  plus one singleton `UsersRoom` DO for accounts/auth/membership.
- `android/` — Kotlin + Compose + Room, package `za.co.wonderlys`.
- `protocol/` — the wire contract. Both sides depend on it; it depends on nothing.
- `web/` — deferred, priority 3, not built yet.

## Rules most often gotten wrong

- **`protocol/` first.** A wire type does not get declared in `server/src` or hand-written in
  Kotlin until it exists in `protocol/src`, documented in `protocol/PROTOCOL.md`, and
  exemplified in `protocol/fixtures/`. See [F1](docs/CODE_STANDARD.md#standard-f1).
- **`domain/` is pure, on both sides.** `server/src/domain/` MUST NOT import `storage/`, touch
  `env`, call `fetch`, `Date.now()`, or `crypto.randomUUID()`. `android/.../domain/` MUST NOT
  import `android.*` or `androidx.*`. Time and identity are arguments, not ambient calls.
- **Never `fallbackToDestructiveMigration()`** in the Android app. It silently wipes the local
  outbox — unsynced user data loss with no error shown. See
  [G2](docs/CODE_STANDARD.md#standard-g2).
- **Sequence numbers are assigned inside the DO**, in the same transaction as the change-row
  write. Never in the Worker, never derived from a client or server timestamp taken elsewhere.
- **Deletes are tombstones.** No `DELETE FROM`. See
  [F5.3](docs/CODE_STANDARD.md#standard-f5).
- **No secrets in `wrangler.jsonc` `vars`** — that file is committed. Secrets go through
  `wrangler secret put` and are gitignored locally as `.dev.vars`.
- **`ListRoom` never checks membership.** The Worker authorizes every request against
  `UsersRoom` before forwarding. See [L3](docs/CODE_STANDARD.md#standard-l3).
- **FCM payloads carry no list content**, ever — data-only, `{type, listId, seq}`. See
  [M1](docs/CODE_STANDARD.md#standard-m1).
- **Anything tagged (SYNC) in the standard is a hard line**, not a style preference. Relaxing
  one requires an ADR, not a comment explaining why it seemed fine this once.

## Where to look next

- `docs/CODE_STANDARD.md` — the full standard, A through N.
- `docs/SYNC.md` — how sync actually works and why.
- `docs/adr/` — decisions already made; read before re-proposing an alternative.
