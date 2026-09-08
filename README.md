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
cp .dev.vars.example .dev.vars   # fill in JWT_SIGNING_KEY etc.
npm run dev                      # wrangler dev, local Durable Object storage
```

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

```bash
cd server && npm test        # Vitest in workerd
cd protocol && npm test      # Vitest, Node
cd android && ./gradlew testDebugUnitTest ktlintCheck detekt
```

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
