# Plan

Where Dielys is and what comes next. Short by design — the standard is in
[CODE_STANDARD.md](CODE_STANDARD.md), the reasoning in [SYNC.md](SYNC.md) and
[adr/](adr/). This is only the running order.

## Done

- [x] **Standard and scaffold** — `docs/CODE_STANDARD.md` A–N, ADR 0001 (Workers + DO,
      including the finding that DOs do not spawn in Africa), ADR 0002 (auth), repo
      structure, CI, PR template.
- [x] **Toolchain** — Android builds green on AGP 9 / Gradle 9.6 / Kotlin 2.4 / compileSdk 37,
      bumped as one locked set because each piece alone fails.
- [x] **Local-first verification** — `scripts/verify.sh` runs everything `ci.yml` runs in
      ~20s. Toolchain at `~/.dielys-toolchain/`. CI is a sense check, not the place bugs
      are found.
- [x] **Protocol v2** — wire types, `PROTOCOL.md`, and a fixture per message type, including
      the F4 edge cases (max-length title, conflicting update pair, empty collection).
- [x] **`ListRoom`** — seq assignment in the same transaction as the write, idempotency keys,
      per-field LWW with device-id tiebreak, sticky tombstones, hibernating WebSocket,
      `?since=N` catch-up. H3 scenarios 3.2, 3.3, 3.5, 3.6, 3.7, 3.8, 3.10, 3.11, 3.12.
- [x] **Auth** — `UsersRoom`, rotating refresh tokens with replay detection, admin-only
      account creation, invite tokens, membership checked by the Worker only (L3).
- [x] **Dev deployment** — live at `dielys-dev.dielys.workers.dev`, secrets set,
      `scripts/smoke.sh` exercises the whole contract against it.
- [x] **Fractional indexing** — base-62 order keys in `server/src/domain/position.ts`, with
      golden vectors in `protocol/fixtures/positions.json` for the Kotlin client to match.
      Appends stay two characters for 62 items; `betweenMany` bisects so bulk inserts do not
      nest. Ordering is `(position, id)` — two devices inserting at the same spot offline can
      produce the same key, and the id breaks the tie (H3.9).
- [x] **Android data layer** — Room schema and DAOs, the outbox, the `WorkManager` drain, and
      the Kotlin port of the fractional index that reproduces every golden vector. `domain/`,
      `data/{local,remote,sync}` and `di/` are real; the UI is still a placeholder. Every user
      action commits its entity and its outbox row in one transaction (F5.7), and the cursor
      moves only after a change is committed locally (F5.8).
- [x] **The rest of H3** — 3.1, 3.4 and 3.9 are covered by JVM tests against a fake server:
      a write made offline drains when the network returns, a drain killed after the server
      applied replays the same idempotency key without duplicating, and both devices dragging
      into the same gap converge on one order. Room runs under Robolectric, so real SQLite
      settles the `(position, id)` tie the same way the phone will.
- [x] **Cross-language wire check** — `WireFormatTest` round-trips every fixture in
      `protocol/fixtures/` through the Kotlin types, and Konsist enforces the E1 layer rules.
- [x] **Compose UI** — sign in, list of lists, list detail, add/tick/star/rename/delete, and
      long-press drag to reorder. Every screen reads Room and writes through the repository;
      nothing on a screen touches the network. No navigation library — the back stack is one
      nullable list id. Also closes two gaps the UI made visible: `/auth/memberships` is now
      pulled on every sync, so a second phone actually discovers the household's lists, and a
      dead outbox row is shown rather than merely kept.

- [x] **FCM wake push** (section M) — data-only payloads carrying `{type, listId, seq}` and
      nothing else, device tokens not topics. A `ListRoom` write hands `UsersRoom` the device
      ids it can see on sockets; `UsersRoom` owns the membership fan-out, the OAuth token
      cache and the dead-token cleanup, so `ListRoom` still never learns who a list's members
      are (L3). `seq` travels as a decimal string because FCM's `data` is `map<string,string>`
      — the key set and the no-content rule are unchanged. An unset `FCM_SERVICE_ACCOUNT_JSON`
      is not fail-closed: without it a mutation still commits and the app falls back to the
      socket and the half-hourly floor (H3.12).

## Next

- [ ] **Dev shakedown** — run the debug build against `dielys-dev` on two phones and work
      through H3 by hand. The parts a JVM test cannot reach are the drag gesture, the keyboard,
      and what a real flaky signal does to the drain. Needs the two manual credential steps
      first: create the Firebase project and drop `android/app/google-services.json` in, then
      `wrangler secret put FCM_SERVICE_ACCOUNT_JSON` for the dev Worker.
- [ ] **Prod** — `dielys-prod` has never been deployed. Needs its own secrets and a smoke run.

## Open questions

- **PBKDF2 work factor.** 10,000 iterations, capped by the free plan's 10 ms CPU budget.
  Confirmed working in the deployed DO. Whether a DO actually gets its own 30 s budget is
  unresolved — the Workers and Durable Objects limits pages disagree — and settling it is one
  probe. If it does, `PASSWORD_ITERATIONS` goes to 600,000; the per-user column means
  existing accounts re-hash on next login rather than breaking.
- **Web client** (`web/`) is priority 3 and unstarted. Note that the browser cannot set
  headers on a WebSocket upgrade, which is how the Android client authenticates — that needs
  an answer before `web/` is real.
