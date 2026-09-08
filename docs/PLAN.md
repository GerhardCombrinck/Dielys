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

## Next

- [ ] **Android data layer** — Room schema, DAOs, the outbox, and the drain worker. This is
      where F5.7 (local write and outbox row in one transaction) and F5.8 (cursor advances
      only after the local commit) actually live.
- [ ] **The rest of H3** — 3.1, 3.4, 3.9 are client-side and cannot be tested server-side.
- [ ] **Compose UI** — list of lists, list detail, add/tick/star/reorder. Nothing beyond a
      placeholder exists.
- [ ] **FCM** (section M) — data-only payloads carrying `{type, listId, seq}` and no list
      content, device tokens not topics.
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
