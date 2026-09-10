# AGENTS.md

Repo shape and rules an AI assistant (or a new human contributor) most often gets wrong here.
Full detail lives in `docs/CODE_STANDARD.md` — this is the summary that matters before you
write a line of code.

## Shape

One repo, two runtimes, one contract:

- `server/` — Cloudflare Workers + Durable Objects, TypeScript. One `ListRoom` DO per list,
  plus one singleton `UsersRoom` DO for accounts/auth/membership.
- `android/` — Kotlin + Compose + Room, package `za.co.dielys`.
- `protocol/` — the wire contract. Both sides depend on it; it depends on nothing.
- `web/` — deferred, priority 3, not built yet.

## Before you push

```bash
scripts/verify.sh
```

Runs everything `ci.yml` runs, locally, in about 40 seconds. CI is a sense check on a clean
machine — not the place a compile error is meant to be discovered, and not something to
spend a runner minute on per commit. Push when the change is complete and testable. See
[B3](docs/CODE_STANDARD.md#standard-b3) and `scripts/AGENTS.md`.

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
- **Login answers the same way for a wrong password and an unknown account** — same status,
  same body, same timing (the login path hashes even when there is no such user). Three
  answers would be an account-enumeration oracle. A list you are not a member of is **403,
  not 404**, for the same reason.
- **`PASSWORD_ITERATIONS` is 10,000 on purpose**, capped by the free plan's 10 ms CPU budget.
  It is stored per user so it can be raised without locking anyone out. The real control is
  that passwords are generated, not chosen. See
  [L1](docs/CODE_STANDARD.md#the-pbkdf2-work-factor).
- **FCM payloads carry no list content**, ever — data-only, `{type, listId, seq}`. See
  [M1](docs/CODE_STANDARD.md#standard-m1).
- **Anything tagged (SYNC) in the standard is a hard line**, not a style preference. Relaxing
  one requires an ADR, not a comment explaining why it seemed fine this once.

## Android dev workflow

- **`ENVIRONMENT === "dev"` disables all auth rate limits** (`UsersRoom.consume()`). Prod stays
  limited; only the `dielys-dev` deployment is open, so testing login repeatedly doesn't trip
  anything.
- **Redeploying to a USB-connected phone does not need to log you out.** `SessionStore` keeps
  `deviceId` and `refreshToken` in plain `SharedPreferences`, which survives a normal
  replace-install (`./gradlew installDebug`, or Android Studio's default Run). If a device gets
  logged out on every deploy, check Android Studio's Run/Debug config for "Clear app storage"
  and uncheck it — that's an app-data wipe, not a session-length problem. Refresh tokens are
  device-scoped and rotate on a 30-day sliding window (`REFRESH_TOKEN_TTL_MS`), so a session
  that's actually used stays alive indefinitely.
- **CLI Gradle builds need JDK 21**, not whatever `java` resolves to on `PATH`. Use the
  project's toolchain JDK, e.g. `JAVA_HOME=.../.dielys-toolchain/jdk21 ./gradlew installDebug`.
  `JAVA_HOME` pointed at an older JDK fails `compileDebugJavaWithJavac` with "invalid source
  release: 21".
- **App Links (`dielys.com/magic`) verification is two independent things**, both of which have
  to be right for a magic-link email to open the app instead of the browser:
  1. `dielys.com/.well-known/assetlinks.json` — served by whichever Worker owns the
     `dielys.com` custom domain route (prod, per `wrangler.jsonc`) — must list the signing
     cert's SHA-256 fingerprint in the `ANDROID_CERT_SHA256_FINGERPRINTS` secret
     (comma-separated; a debug build needs its debug-keystore fingerprint added too).
  2. The device's own per-app link setting must not have the domain disabled — check with
     `adb shell dumpsys package za.co.dielys | grep -A10 "Domain verification status"`;
     re-enable with
     `adb shell pm set-app-links-user-selection --user 0 --package za.co.dielys true dielys.com`.
     A domain can show `verified` and still open in the browser if this is off.

## Where to look next

- `docs/PLAN.md` — what is built, what is next, and the questions still open.
- `docs/CODE_STANDARD.md` — the full standard, A through N.
- `docs/SYNC.md` — how sync actually works and why.
- `docs/adr/` — decisions already made; read before re-proposing an alternative.
