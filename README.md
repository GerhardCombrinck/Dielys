## Dielys

A Wunderlist-style shared to-do / shopping list for a household of two. Local-first,
offline-tolerant, syncs over Cloudflare Durable Objects.

### Requirements

- Node.js 22+, npm
- JDK 21, Android Studio (or command-line Gradle) for the Android client
- A Cloudflare account (free plan) for deploying the server
- `wrangler` CLI (installed via `npm ci` in `server/`)

### Getting Started

```bash
git clone https://github.com/GerhardCombrinck/Dielys.git
cd Dielys
```

**Server:**

```bash
cd server
npm ci
cp .dev.vars.example .dev.vars   # fill in JWT_SIGNING_KEY and ADMIN_TOKEN
npm run dev                      # wrangler dev, local Durable Object storage
```

`.dev.vars` is gitignored and local-only. Generate real values into it — the Worker refuses to
serve anything but `/health` when `JWT_SIGNING_KEY` is missing or shorter than 32 characters,
because an unset binding would otherwise sign tokens with the literal string `"undefined"`:

```bash
node -e 'const r=()=>require("crypto").randomBytes(32).toString("base64url");require("fs").writeFileSync("server/.dev.vars",`JWT_SIGNING_KEY=${r()}
ADMIN_TOKEN=${r()}
FCM_SERVICE_ACCOUNT_JSON={}
`)'
```

For a deployed environment the same secrets are set with `wrangler secret put
JWT_SIGNING_KEY --env dev` and `wrangler secret put ADMIN_TOKEN --env dev`; both are long
random values and neither ever goes in `wrangler.jsonc`
([I1](docs/CODE_STANDARD.md#standard-i1)).

`FCM_SERVICE_ACCOUNT_JSON` is the third, and the only optional one: `wrangler secret put
FCM_SERVICE_ACCOUNT_JSON --env dev`, pasting the whole service-account JSON on one line.
Without it the Worker runs normally and simply never sends a wake push — a phone then hears
about a change over its WebSocket, or on the half-hourly sync
([H3.12](docs/CODE_STANDARD.md#standard-h3)). The Android half needs
`android/app/google-services.json` from the same Firebase project; it is gitignored, and a
build without it still works the same way.

### Local development

`wrangler dev` runs the real `workerd` runtime with real Durable Object storage on your
machine — not a mock, and not a simulator. Behaviour matches production except for platform
limits, which are only enforced on Cloudflare's network.

```bash
cd server && npm run dev
```

Serves on `http://127.0.0.1:8787`. State persists in `.wrangler/` between runs; delete that
directory for a clean slate. A full walk through the API against it:

```bash
ADMIN=$(grep '^ADMIN_TOKEN=' server/.dev.vars | cut -d= -f2); B=http://127.0.0.1:8787
```

```bash
curl -s -X POST $B/admin/users -H "Authorization: Bearer $ADMIN" -H 'content-type: application/json' -d '{"email":"you@dielys.test","password":"a-generated-password-1234"}'
```

```bash
TOKEN=$(curl -s -X POST $B/auth/login -H 'content-type: application/json' -d '{"email":"you@dielys.test","password":"a-generated-password-1234","deviceId":"phone-a"}' | node -pe 'JSON.parse(require("fs").readFileSync(0,"utf8")).accessToken')
```

```bash
LIST=$(node -pe 'crypto.randomUUID()'); curl -s -X POST "$B/lists/$LIST" -H "Authorization: Bearer $TOKEN"
```

```bash
curl -s -X POST "$B/lists/$LIST/mutate" -H "Authorization: Bearer $TOKEN" -H 'content-type: application/json' -d "{\"type\":\"mutate\",\"protocolVersion\":2,\"listId\":\"$LIST\",\"entityType\":\"task\",\"entityId\":\"$(node -pe 'crypto.randomUUID()')\",\"idempotencyKey\":\"$(node -pe 'crypto.randomUUID()')\",\"deviceId\":\"phone-a\",\"patch\":{\"title\":\"Melk\",\"position\":\"a0\"}}"
```

```bash
curl -s "$B/lists/$LIST/changes?since=0" -H "Authorization: Bearer $TOKEN"
```

Re-sending a mutation with the same `idempotencyKey` returns the original change with
`"duplicate": true` and does not add a second changelog row — that is [F5.2](docs/SYNC.md)
working, and is worth seeing once by hand.

Endpoints are listed in [protocol/PROTOCOL.md](protocol/PROTOCOL.md).

### Creating an account

Registration is open ([ADR 0004](docs/adr/0004-open-registration.md)): anyone with the Worker
URL can make an account from the app's sign-up screen, which is what makes it possible to
share a list with someone who does not already have one. Both registration and login are rate
limited per client and, for registration, globally per day.

Accounts can still be made from the command line, which is how the first one on a fresh
deployment gets made:

```bash
DIELYS_URL=https://dielys-dev.dielys.workers.dev DIELYS_ADMIN_TOKEN=... node --experimental-strip-types scripts/create-user.ts you@example.com
```

It prompts for confirmation and then for the password. Use a generated one — see
`scripts/AGENTS.md` for why that matters here.

### Sharing a list

The owner of a list shares it from the list's menu. That mints a seven-day invite token
([L3](docs/CODE_STANDARD.md#standard-l3)) wrapped in a `dielys://invite?t=...` link and hands
it to the share sheet. The invite is a bearer credential — whoever holds it joins the list —
so it goes to one person, not into a group chat. Tapping the link on a phone that has the app
offers to join; the app never joins on its own, because a link is something anyone can send.

**Protocol** (shared types, no server needed to build it):

```bash
cd protocol
npm ci
npm run build
```

**Android:**

Open `android/` in Android Studio, or:

```bash
cd android
./gradlew assembleDebug
```

### Project Layout

| Path        | What                                                      |
|-------------|------------------------------------------------------------|
| `server/`   | Cloudflare Workers + Durable Objects backend (TypeScript)  |
| `protocol/` | The wire contract — types and fixtures shared by both sides |
| `android/`  | Kotlin + Compose + Room Android client                     |
| `web/`      | Deferred web client — not yet built (priority 3)           |
| `docs/`     | Coding standard, ADRs, sync design                          |
| `scripts/`  | One-off and maintenance scripts                              |

### Running Tests

Everything CI runs, locally, in about 40 seconds — run this before pushing:

```bash
scripts/verify.sh
```

`FAST=1 scripts/verify.sh` skips the Android leg. One component at a time, if you prefer:

```bash
cd server && npm test        # Vitest in workerd
cd protocol && npm test      # Vitest, Node
cd android && ./gradlew testDebugUnitTest ktlintCheck detekt
```

The Android leg needs a JDK 21 and an Android SDK; `scripts/verify.sh` finds them via
`JAVA_HOME`/`ANDROID_HOME` or `~/.dielys-toolchain/`, and skips that leg with a warning if
neither is present. See `scripts/AGENTS.md`.

### Deploying

Push to `main` auto-deploys `server/` to the `dev` Cloudflare environment
(`dielys-dev`). Tagging `v*` deploys `prod` (`dielys-prod`, gated on a required
reviewer) and builds a signed Android release APK attached to the GitHub Release.

### Rolling Back

Server: `wrangler rollback` from `server/`, or redeploy the previous `v*` tag through the
`deploy-server.yml` workflow. Migrations are lazy per Durable Object and forward-only — rolling
back code does not roll back schema (see [G1](docs/CODE_STANDARD.md#standard-g1)).

Android: there is no live rollback for an installed APK; a fixed release must be shipped as a
new version.

### Authors

- Gerhard Combrinck ([gerhard@invisionsoft.co.za](mailto:gerhard@invisionsoft.co.za))
