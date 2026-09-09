# scripts/AGENTS.md

Utility and maintenance scripts. POSIX `sh` or Node only — CI runs on Linux
([A2](../docs/CODE_STANDARD.md#standard-a2)).

| Script            | What                                                        | Touches prod data |
|-------------------|--------------------------------------------------------------|--------------------|
| `verify.sh`       | Runs everything `ci.yml` runs, locally. Use before every push ([B3](../docs/CODE_STANDARD.md#standard-b3)). | No |
| `smoke.sh`        | End-to-end check against a running server — local, dev or prod. Run after a deploy. | Creates two throwaway accounts, one list and two device rows |
| `push-probe.sh`   | Makes a deployed server attempt one real FCM send, to prove the credential works. Read `wrangler tail` for the answer. | Creates one throwaway account and one list |
| `create-user.ts`  | Creates one Dielys account in `UsersRoom`. See [L2](../docs/CODE_STANDARD.md#standard-l2) — there is no public registration endpoint, this is the only way an account gets created. | Yes |

A script that touches production data MUST print what it is about to do and prompt for
confirmation before doing it. Anything run by hand more than twice becomes a script here.

## Running these on Windows

Use **Git Bash**, not PowerShell and not WSL. `bash` on a default Windows PATH resolves to
`C:\WINDOWS\system32\bash.exe`, which is the WSL launcher and fails with "no installed
distributions" on a machine that has never set WSL up — a confusing error, because the shell
it names does exist, just not the one you wanted.

Either open Git Bash directly, or call it by path from PowerShell so the current environment
carries over:

```powershell
& "C:\Program Files\Git\bin\bash.exe" scripts/smoke.sh https://dielys-dev.dielys.workers.dev
```

Three differences from PowerShell: paths are `/c/repos/Dielys`, environment variables are
`export NAME='value'`, and paste is Shift+Insert. Git Bash is also what `verify.sh` assumes
and closest to the Linux shell CI runs, so a script that works there works in CI.

## smoke.sh

```sh
DIELYS_ADMIN_TOKEN=... scripts/smoke.sh https://dielys-dev.dielys.workers.dev
```

27 checks over the whole contract: login, list claim, mutation, an idempotent
retry that must return the original result at the same seq without adding a
changelog row, catch-up, invite mint and accept, push-token registration, and
refresh rotation with replay detection. Exits non-zero on the first disagreement and prints the body.

It is not read-only: it creates two `smoke-*@dielys.test` accounts and one list
per run, and names them at the end. Nothing deletes them — there is no account
deletion endpoint, deliberately (L2). On dev that is fine; think before pointing
it at prod.

`scripts/verify.sh` proves the code is right before a push; this proves the
deployment is right after one. Neither replaces the other.

## push-probe.sh

```sh
npx wrangler tail --env dev --format pretty   # in one window
DIELYS_ADMIN_TOKEN=... scripts/push-probe.sh https://dielys-dev.dielys.workers.dev
```

`smoke.sh` proves `/devices/token` stores a token. It cannot prove the server can
then authenticate to Google, because the send is best-effort and off the response
path (M2) — a mutation acks whether or not the push went anywhere. This drives
that path deliberately.

It registers an FCM token that is invalid on purpose and then writes from a second
device on the same account. **`fcm.send.rejected` with status 400 is the pass**: FCM
only objects to a token after it has authenticated the request, matched the project
and accepted the body, so a 400 proves everything a credential can be wrong about.
No `usersroom.device.dropped` follows it, and should not — a device row is deleted
on 404 and never on 400 (ADR 0003), because a 400 can be our own malformed request.

`fcm.send.unregistered` is out of reach here. That needs a token FCM recognises as
well-formed but no longer registered, which only a real wiped device produces; the
server suite covers the 404 path instead. The script prints what each other log line
means.

The script itself cannot fail on a credential problem, and does not pretend to —
it exits 0 as long as the HTTP contract held, and hands you the tail to read.

## create-user.ts

```sh
DIELYS_URL=https://dielys-dev.dielys.workers.dev \
DIELYS_ADMIN_TOKEN=<the ADMIN_TOKEN secret for that environment> \
node --experimental-strip-types scripts/create-user.ts someone@example.com
```

It calls the Worker's `/admin/users` route rather than touching storage, so it behaves the
same against dev and prod and needs no local binding. The password is **prompted for, never
passed as an argument** — argv lands in shell history and in the process list.

Use a password manager to generate it. That is not a style preference: `PASSWORD_ITERATIONS`
is capped at 10,000 by the free plan's CPU budget, and a generated password is what makes
that safe ([L1](../docs/CODE_STANDARD.md#the-pbkdf2-work-factor)).

## verify.sh

```sh
scripts/verify.sh                 # protocol, server, android
scripts/verify.sh server          # one component
FAST=1 scripts/verify.sh          # skip android, the slow leg
CLEAN=1 scripts/verify.sh         # reinstall node deps from the lockfile, as CI does
```

Every component runs even if an earlier one fails, so one run reports everything that is
broken rather than the first thing.

Node dependencies are reinstalled only when `package-lock.json` is newer than the last
install, or under `CLEAN=1`. CI always does a full `npm ci`, so `CLEAN=1` is the honest
check before a push that changed a lockfile.

### Toolchain discovery

`JAVA_HOME` and `ANDROID_HOME` are used when they are already set — but `JAVA_HOME` is
ignored unless it really is a JDK 21, because AGP 9 fails on anything older with `invalid
source release: 21`, which does not point at the real cause.

Otherwise both fall back to `~/.dielys-toolchain/` (`jdk21/`, `android-sdk/`), overridable
with `DIELYS_TOOLCHAIN`. If neither resolves, the Android leg is **skipped with a warning
and the run still passes** — a machine without an Android SDK can still verify the
TypeScript half, and pretending otherwise would make the script useless there. Read the
`skipped:` line before trusting a green run.

`android/local.properties` is gitignored and points at the same SDK; it is per-machine and
is not something to commit.

### If `npm ci` fails with EPERM or EBUSY

Windows only, and self-inflicted: a running `wrangler dev` holds
`node_modules/@esbuild/win32-x64/esbuild.exe` and miniflare's `local-explorer-ui`, and `npm
ci` deletes `node_modules` before reinstalling. Stop the dev server first. The half-deleted
tree that gets left behind also makes `npx tsc` fall through to whatever `tsc` is on the
Windows PATH — often Turbo C, which prints "This is not the tsc command you are looking
for". That message means the install is broken, not the TypeScript config.
