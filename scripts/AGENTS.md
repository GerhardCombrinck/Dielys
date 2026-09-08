# scripts/AGENTS.md

Utility and maintenance scripts. POSIX `sh` or Node only — CI runs on Linux
([A2](../docs/CODE_STANDARD.md#standard-a2)).

| Script            | What                                                        | Touches prod data |
|-------------------|--------------------------------------------------------------|--------------------|
| `verify.sh`       | Runs everything `ci.yml` runs, locally. Use before every push ([B3](../docs/CODE_STANDARD.md#standard-b3)). | No |
| `create-user.ts`  | Creates one Dielys account in `UsersRoom`. See [L2](../docs/CODE_STANDARD.md#standard-l2) — there is no public registration endpoint, this is the only way an account gets created. | Yes |

A script that touches production data MUST print what it is about to do and prompt for
confirmation before doing it. Anything run by hand more than twice becomes a script here.

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
