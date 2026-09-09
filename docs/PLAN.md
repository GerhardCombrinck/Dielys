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
- [x] **Android WebSocket client** — `SyncSockets` supervises one `ListSocketSession` per
      list the account is a member of, driven by sign-in state and the Room list-set Flow
      rather than by explicit calls. The upgrade carries the access token as
      `Authorization: Bearer` (L3). Closes M2's vacuous rule that a connected device must
      not also get a push — until this, no Android device ever held a connection.
- [x] **Open registration and invite UI** (ADR 0004) — `POST /auth/register` is public;
      `UsersRoom` now rate-limits registration and login before spending a PBKDF2 round,
      keyed by `HMAC-SHA256(JWT_SIGNING_KEY, ip)` so the DO never sees an address (D4).
      Sign-in/sign-up is one screen on the phone; invites travel as
      `dielys://invite?t=...`, never displayed, parked in `PendingInvite` until accepted.
      `scripts/smoke.sh` and `scripts/push-probe.sh` make their own accounts through the
      public route now instead of holding `DIELYS_ADMIN_TOKEN`.
- [x] **Rotate the FCM service-account key** — `gcloud` installed via `winget` and
      authenticated, so the rotation ran from a shell after all. The replacement key was
      created and piped into `wrangler secret put` directly rather than saved to a file —
      except that on this Windows/Git-Bash setup, `gcloud ... keys create /dev/stdout`
      does not actually write to stdout: MSYS rewrites `/dev/stdout` to `/proc/self/fd/1`
      in the argument list before `gcloud` ever sees it, and gcloud's native Windows Python
      runtime resolves that leading `/` against the current drive, so the key was silently
      written to a real file at `C:\proc\self\fd\1` — the exact disk exposure the "never
      touches disk" approach was meant to avoid. Found by checking gcloud's own debug log
      (`%APPDATA%\gcloud\logs\`) for the resolved `OUTPUT-FILE` argument, uploaded correctly
      from that file with `cat`, verified live with `push-probe.sh` (`fcm.send.rejected` 400),
      then deleted the file immediately. **Lesson recorded in the README runbook: on Windows,
      use a real file and delete it after, exactly as originally documented — do not try to
      pipe from `/dev/stdout`.** Two stray keys from failed pipe attempts and the two old keys
      were all deleted from GCP afterward; a third, `c2110538...`, turned out to be
      system-managed (Google's own, private key never distributed) and cannot be deleted —
      correctly so, and it was never part of the exposure.

## Next

- [x] **Dev shakedown** — ran the debug build against `dielys-dev` on two phones by hand:
      offline add/reorder on both, double-tick convergence, delete-vs-rename race, and FCM
      wake push to a backgrounded phone. All twelve H3 scenarios held up outside the JVM tests.
- [ ] **Visual redesign** (Navy/Sand/Amber, "Die Lys" wordmark) — login, lists, and task-list
      screens rebuilt in Compose from the design handoff in `docs/`, plus a password-visibility
      toggle, a settings screen with account details, join-a-list moved there, and a
      collapsible Done section. Needs a look on a real phone before this is called finished —
      the handoff's pixel values were followed but never checked against the mock on-device.
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
