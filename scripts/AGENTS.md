# scripts/AGENTS.md

Utility and maintenance scripts. POSIX `sh` or Node only — CI runs on Linux
([A2](../docs/CODE_STANDARD.md#standard-a2)).

| Script            | What                                                        | Touches prod data |
|-------------------|--------------------------------------------------------------|--------------------|
| `verify.sh`       | Runs everything `ci.yml` runs, locally. Use before every push ([B3](../docs/CODE_STANDARD.md#standard-b3)). | No |
| `node-tests.sh`   | Runs one node component's tests, bounded and retried past [workers-sdk#15498](https://github.com/cloudflare/workers-sdk/issues/15498). Called by `verify.sh` and by `ci.yml`. | No |
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
scripts/smoke.sh https://dielys-dev.dielys.workers.dev
```

29 checks over the whole contract: registration, login, list claim, mutation, an
idempotent retry that must return the original result at the same seq without
adding a changelog row, catch-up, invite mint and accept, push-token
registration, and refresh rotation with replay detection. Exits non-zero on the
first disagreement and prints the body.

No admin token any more: registration is public ([ADR 0004](../docs/adr/0004-open-registration.md)),
so the script makes its own accounts. The one check that still concerns
`/admin/users` sends no token and expects to be refused, which is how it stays
honest without holding a secret.

**Once per hour against a deployed environment.** A run spends all three of the
registrations a client gets per hour, so a second run inside the hour fails at
the first step and proves nothing. `push-probe.sh` shares that budget. A local
`wrangler dev` keeps its counters in a throwaway DO, so restarting clears them.

It is not read-only: it creates two `smoke-*@dielys.test` accounts and one list
per run, and names them at the end. Nothing deletes them — there is no account
deletion endpoint, deliberately (L2). On dev that is fine; think before pointing
it at prod.

`scripts/verify.sh` proves the code is right before a push; this proves the
deployment is right after one. Neither replaces the other.

## push-probe.sh

```sh
npx wrangler tail --env dev --format pretty   # in one window
scripts/push-probe.sh https://dielys-dev.dielys.workers.dev
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

This is also the check to run after rotating the FCM service-account key: a
`fcm.send.rejected` 400 means the new key authenticated, and anything earlier in
the log means it did not.

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

### If the server tests pass and then nothing happens

Known upstream bug, not your change: [cloudflare/workers-sdk#15498](https://github.com/cloudflare/workers-sdk/issues/15498).
The Workers test pool starts one `workerd` per test file and, on roughly two runs in five,
starts one it never stops. Every test passes in about twelve seconds, the summary never
prints, and the run sits there — for as long as you let it. A hung run and a green run
produce byte-identical test output; the only difference is a started-but-never-stopped pool
worker whose child process keeps Node's event loop alive.

`server/vitest.config.ts` sets `fileParallelism: false`, which is what actually fixes this:
one test file at a time means the pool never has two workers starting and stopping at once,
and the race needs that overlap. Measured here: 42% of runs hang with files in parallel,
about 7% without. It costs roughly 8 seconds a run.

Under the remainder, `scripts/node-tests.sh` bounds each attempt at `DIELYS_TEST_TIMEOUT`
seconds (45 by default, and `ci.yml` sets 150 for a slower runner), kills the `workerd` processes that stuck attempt left behind, and
tries again — three attempts in all (`DIELYS_TEST_ATTEMPTS`). Hanging every time is reported
as this bug rather than as a test failure. `verify.sh` and `ci.yml` both run the tests
through that script, so the local guard and the CI guard cannot drift apart. A retry
prints:

```
warn server tests hung at 45s (workers-sdk#15498), attempt 2 of 3.
```

That line means the tooling tripped, not that anything regressed.

Only workerd processes that appeared *during* the run are killed, so a `wrangler dev` in
another window survives. If you kill a hung run yourself, its `workerd` is orphaned and
outlives the shell — worth checking before blaming the next run:

```powershell
Get-Process workerd -ErrorAction SilentlyContinue | Select-Object Id, StartTime
```

Bumping `miniflare` past the pool's pinned `5.20260815.0-alpha` does not help: 6 hangs in 15
runs either way. What the hung run leaves behind is one pool worker that was started and
never stopped — count `start`/`stop` pairs and a green run is 13/13, a hung one 13/12.

### If a run takes tens of minutes

Check for a running emulator first. `vitest-pool-workers` starts several `workerd`
processes, and two AVDs will happily take every core on a four-core machine — a run that
finishes in 22 seconds otherwise took over half an hour alongside them. Nothing is hung
and nothing reports it, because `verify.sh | tail` shows no output at all until the run
ends. Shut the emulators down (`adb -s emulator-5554 emu kill`) rather than waiting.

### If `npm ci` fails with EPERM or EBUSY

Windows only, and self-inflicted: a running `wrangler dev` holds
`node_modules/@esbuild/win32-x64/esbuild.exe` and miniflare's `local-explorer-ui`, and `npm
ci` deletes `node_modules` before reinstalling. Stop the dev server first. The half-deleted
tree that gets left behind also makes `npx tsc` fall through to whatever `tsc` is on the
Windows PATH — often Turbo C, which prints "This is not the tsc command you are looking
for". That message means the install is broken, not the TypeScript config.
