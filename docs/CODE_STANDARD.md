# Wonderlys Development Coding Standard

- [Wonderlys Development Coding Standard](#wonderlys-development-coding-standard)
  - [Introduction \& Requirements Summary](#introduction--requirements-summary)
  - [A: Repository Structure](#a-repository-structure)
  - [B: Code Style \& Formatting](#b-code-style--formatting)
  - [C: Source Control \& Git Configuration](#c-source-control--git-configuration)
  - [D: TypeScript \& Workers Standards](#d-typescript--workers-standards)
  - [E: Kotlin \& Android Standards](#e-kotlin--android-standards)
  - [F: The Sync Contract](#f-the-sync-contract)
  - [G: Schema \& Migrations](#g-schema--migrations)
  - [H: Testing](#h-testing)
  - [I: Secrets \& Configuration](#i-secrets--configuration)
  - [J: CI/CD \& Deployment](#j-cicd--deployment)
  - [K: Documentation Standards](#k-documentation-standards)
  - [L: Authentication](#l-authentication)
  - [M: Push Notifications (FCM)](#m-push-notifications-fcm)
  - [N: Dependency Policy](#n-dependency-policy)
  - [Appendix A: Compliance Checklist](#appendix-a-compliance-checklist)
  - [Appendix B: Reference Templates](#appendix-b-reference-templates)

## Introduction & Requirements Summary

**Reference Repository:** `Wonderlys` (this repo is its own gold standard — keep it that way)

---

### Purpose and Scope

This document defines the coding standard and standard operating procedure for the Wonderlys
shared to-do application: a Cloudflare Workers + Durable Objects backend and a native Kotlin
Android client, sharing one wire protocol.

It exists because this repo spans **two languages, two runtimes and one contract between them**.
That is precisely the shape of project that rots into patchwork if the rules are decided
per-commit. Everything below is decided once, here, before the first line of product code.

**Components in scope:**

| Component  | Path        | Language / Runtime                    | Priority |
|------------|-------------|---------------------------------------|----------|
| Server     | `server/`   | TypeScript — Cloudflare Workers + DO  | 1        |
| Protocol   | `protocol/` | TypeScript types + JSON fixtures      | 1        |
| Android    | `android/`  | Kotlin — Jetpack Compose + Room       | 1        |
| Web client | `web/`      | TypeScript — deferred, not yet built  | 3        |

**Out of scope:** iOS. The protocol MUST NOT acquire Android-specific assumptions that would
block an iOS client later, but no iOS code is written or maintained.

---

### Audience

- The maintainer (currently one person — future you counts as a second developer)
- AI coding assistants (Claude Code, Copilot) working in this repo
- Any future contributor

---

### Document Conventions

| Term       | Meaning                                                              |
|------------|----------------------------------------------------------------------|
| **MUST**   | Mandatory. Non-compliance is a blocker for merge.                    |
| **SHOULD** | Strongly recommended. Deviations require a note in the PR body.      |
| **MAY**    | Optional. Use when it makes sense.                                   |

The tag **(SYNC)** marks any requirement that exists specifically to protect sync correctness.
These are the rules that MUST NOT be relaxed for convenience — every one of them is there
because breaking it produces a bug that only appears on someone else's phone, in a shop,
with no signal, and cannot be reproduced afterwards.

---

## A: Repository Structure

---

<a id="standard-a1"></a>

### A1 — Monorepo Root Layout

**Requirement ID:** A1
**Applicability:** All work.

#### What

One repository holds server, protocol and clients. Every folder below MUST exist from the
first commit, with a `.gitkeep` where it is initially empty, so the shape is recognisable
before it is populated.

#### Why

The server and the Android client are two halves of one protocol. Splitting them across repos
means a protocol change lands in two PRs that can merge independently — which is exactly how
clients and servers drift. One repo means one PR, one CI run, one review.

#### Standard Root Layout

```
Wonderlys/
├── .github/
│   ├── workflows/
│   │   ├── ci.yml                   ← [J2] Lint, typecheck, test — all components
│   │   ├── deploy-server.yml        ← [J3] Workers deploy (dev auto, prod gated)
│   │   └── release-android.yml      ← [J4] Signed APK on tag
│   └── pull_request_template.md     ← [C4] PR template
├── android/                         ← [E1] Kotlin app (Gradle project root)
│   ├── app/
│   ├── gradle/
│   └── build.gradle.kts
├── server/                          ← [D1] Workers + Durable Objects
│   ├── src/
│   ├── migrations/                  ← [G1] DO SQLite migrations
│   ├── test/
│   └── wrangler.jsonc
├── protocol/                        ← [F1] The wire contract, single source of truth
│   ├── src/
│   ├── fixtures/                    ← [F4] Golden JSON, shared by both sides
│   └── PROTOCOL.md
├── docs/
│   ├── adr/                         ← [K3] Architecture Decision Records
│   ├── CODE_STANDARD.md             ← this file
│   └── SYNC.md                      ← [K2] How sync actually works
├── scripts/                         ← [A2] Utility scripts
├── .editorconfig                    ← [B1]
├── .gitignore                       ← [C1]
├── AGENTS.md                        ← [K4] Root agent guidelines
├── CLAUDE.md                        ← [K5] Index of AGENTS.md files
└── README.md                        ← [K1] Getting started
```

#### Rules

- Product code MUST live under `server/src`, `android/app/src`, or `protocol/src`. Nothing
  product-related at the repo root.
- `protocol/` MUST NOT import from `server/` or `android/`. The dependency arrow points one
  way: both clients depend on the protocol, never the reverse.
- A new top-level folder requires an ADR ([K3]).

---

<a id="standard-a2"></a>

### A2 — `scripts/` Folder

**Requirement ID:** A2
**Applicability:** All repos.

#### What

Utility and maintenance scripts live in `scripts/`, with an `AGENTS.md` describing what each
one does and when to run it.

#### Rules

- Scripts MUST be POSIX `sh` or Node. No PowerShell-only scripts — CI runs on Linux.
- A script that touches production data MUST prompt for confirmation and MUST print what it
  is about to do before doing it.
- Anything run more than twice by hand MUST become a script here.

---

## B: Code Style & Formatting

---

<a id="standard-b1"></a>

### B1 — Standardized `.editorconfig` at Repo Root

**Requirement ID:** B1
**Applicability:** All repos.

#### What

A single `.editorconfig` at the root governs whitespace for every language in the repo.

#### Why

Two languages with different community conventions (TypeScript uses 2 spaces, Kotlin
officially uses 4) will produce noisy diffs unless the difference is declared once, centrally,
rather than left to whichever IDE opened the file.

#### Standard

```ini
root = true

[*]
charset = utf-8
end_of_line = lf
insert_final_newline = true
trim_trailing_whitespace = true
indent_style = space
indent_size = 2
max_line_length = 100

[*.{kt,kts}]
indent_size = 4
ij_kotlin_allow_trailing_comma = true
ij_kotlin_allow_trailing_comma_on_call_site = true

[*.md]
trim_trailing_whitespace = false

[*.{yml,yaml}]
indent_size = 2

[Makefile]
indent_style = tab
```

#### Key Rules Explained

| Rule                       | Value            | Rationale                                                           |
|----------------------------|------------------|---------------------------------------------------------------------|
| `end_of_line`              | `lf`             | Developed on Windows, built on Linux CI. LF everywhere, no CRLF churn |
| `indent_size` (TS/JSON)    | `2`              | Matches Biome default and the wider TS ecosystem                    |
| `indent_size` (Kotlin)     | `4`              | Kotlin official style — do not fight ktlint on this                 |
| `max_line_length`          | `100`            | Wide enough for Kotlin's verbose types, narrow enough for side-by-side diff |
| `trim_trailing_whitespace` | `false` in `.md` | Two trailing spaces is a hard line break in Markdown                |

---

<a id="standard-b2"></a>

### B2 — Formatters and Linters

**Requirement ID:** B2
**Applicability:** All repos.

#### What

| Component        | Tool     | Role                          | Config file        |
|------------------|----------|-------------------------------|--------------------|
| TypeScript       | Biome    | Formatter **and** linter      | `biome.json`       |
| Kotlin           | ktlint   | Formatter and lint            | `.editorconfig`    |
| Kotlin           | detekt   | Static analysis / complexity  | `detekt.yml`       |

#### Why

Biome replaces ESLint + Prettier with one binary and one config. Two tools that both claim
authority over formatting is the single most common source of "CI says the file is wrong but
my IDE says it is right". One tool per language, no exceptions.

#### Rules

- Formatting MUST NOT be argued about in review. If the formatter accepts it, it ships.
- Rules MUST be changed in the config file, never suppressed inline, unless the suppression
  carries a comment explaining why. A bare `biome-ignore` or `@Suppress` with no reason is a
  review blocker.
- New lint rules MAY be added at any time, but the commit that adds a rule MUST also fix
  every existing violation, so `main` is never knowingly failing.

---

<a id="standard-b3"></a>

### B3 — CI Enforcement

**Requirement ID:** B3
**Applicability:** All repos.

#### What

`ci.yml` MUST verify formatting before it compiles or tests anything.

#### Standard Step Order

The order is intentional and MUST NOT be changed:

1. **Checkout**
2. **Format check** — `biome ci` / `ktlintCheck`. Fails fast, costs seconds.
3. **Typecheck** — `tsc --noEmit` / Kotlin compile
4. **Static analysis** — `detekt`
5. **Test** — the expensive step runs last

#### Developer Workflow

```bash
npm run fix
```

```bash
cd android && ./gradlew ktlintFormat
```

Run before pushing. CI is a safety net, not the formatter.

---

## C: Source Control & Git Configuration

---

<a id="standard-c1"></a>

### C1 — Standardized `.gitignore`

**Requirement ID:** C1
**Applicability:** All repos.

#### Required Exclusions

These MUST be present. Each one has been a real leak somewhere:

| Pattern                  | Reason                                                            |
|--------------------------|-------------------------------------------------------------------|
| `.dev.vars`              | **Local Workers secrets.** Committing this leaks the JWT key       |
| `.wrangler/`             | Local Workers state, includes a local SQLite copy                  |
| `node_modules/`          | Dependencies                                                       |
| `local.properties`       | Android SDK paths, machine-specific                                |
| `*.keystore`, `*.jks`    | **Android signing keys.** Never in git, ever                       |
| `*.apk`, `*.aab`         | Build outputs                                                      |
| `.claude/`               | Local AI assistant config                                          |
| `.mcp.json`              | May contain tokens                                                 |
| `build/`, `.gradle/`     | Gradle outputs                                                     |
| `.idea/`, `.vscode/`     | IDE state (except `.vscode/extensions.json`, which MAY be committed) |

#### Rules

- `.gitignore` MUST be updated in the same commit that introduces a new generated artifact.
- A secret that reaches a commit is a rotation event, not a `git rm` event. Rotate the key
  first, remove it second. Assume anything pushed is public forever.

---

<a id="standard-c2"></a>

### C2 — Commit Message Convention

**Requirement ID:** C2
**Applicability:** All repos.

#### Standard Format

```
[#NN] Brief description of what changed
```

- `NN` is the GitHub issue number
- Headline is sentence-case, imperative, concise (50 chars preferred); no trailing period
- A body of up to two short paragraphs MAY follow, separated by a blank line
- AI-assisted commits MUST include a `Co-Authored-By:` trailer after the body

**Examples — headline only:**

```
[#14] Add outbox drain with exponential backoff
[#22] Reject protocol handshake below version 2
[#31] Fix seq cursor advancing past unapplied change
```

**Example — headline + body + AI trailer:**

```
[#31] Fix seq cursor advancing past unapplied change

The cursor was written before the local transaction committed, so a crash
between the two lost the change permanently with no way to detect it.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
```

#### Non-Issue Commits

```
[NO-ISSUE] Brief description of what changed
```

For CI tweaks, formatting, typo fixes. All meaningful work SHOULD have an issue.

#### Commit Guidelines

- One logical change per commit. Protocol change, server change, and client change SHOULD be
  separate commits within one PR.
- **No merge commits** — rebase feature branches onto `main`.
- **Squash and merge** is the required strategy. Enforce in *Settings → General → Allow squash
  merging only*.

#### Branch Naming

```
{NN}-brief-kebab-case-description
```

Examples: `14-outbox-drain-backoff`, `31-seq-cursor-crash`

---

<a id="standard-c3"></a>

### C3 — Branch Protection

**Requirement ID:** C3
**Applicability:** `main`.

#### Standard

`main` MUST be protected with:

- Require a pull request before merging
- Require `ci.yml` to pass
- Require branches to be up to date before merging
- Require linear history
- No force pushes, no deletions

Self-review is acceptable on a solo project — the PR exists to run CI and to leave a readable
record of why, not to satisfy a second pair of eyes that does not exist yet.

> **Plan dependency:** branch protection and rulesets are available on GitHub Free for **public**
> repositories only. See the plan dependency note under [J1] — the same caveat applies here, and
> going private on Free would silently unprotect `main`.

---

<a id="standard-c4"></a>

### C4 — Pull Request Template

**Requirement ID:** C4
**Applicability:** All PRs.

#### Standard Template

```markdown
### Description

<!-- What changed and why. Link the issue: Closes #NN -->

### Protocol impact

<!-- MUST be filled in. One of:
     - No protocol change
     - Additive within version N (old clients unaffected)
     - Breaking — bumps PROTOCOL_VERSION to N, migration plan below -->

### How to test

<!-- Steps. For sync changes, name which scenario in H3 this exercises. -->

### Pre-merge checklist

- [ ] Formatter and linter pass locally
- [ ] Tests added or updated
- [ ] `protocol/fixtures/` updated if the wire format changed
- [ ] Migration added if schema changed, and tested against a populated DB
- [ ] No secret, key, or token in the diff
```

The **Protocol impact** section MUST NOT be deleted or left blank. A PR that touches
`protocol/` with "No protocol change" ticked is a review blocker.

---

## D: TypeScript & Workers Standards

---

<a id="standard-d1"></a>

### D1 — Server Folder Layout

**Requirement ID:** D1
**Applicability:** `server/`.

#### Standard

```
server/
├── src/
│   ├── index.ts              ← Worker entry. Routing and auth only, no business logic
│   ├── auth/
│   │   └── jwt.ts            ← Token verify. No storage access
│   ├── do/
│   │   └── ListRoom.ts       ← The Durable Object class. One per list
│   ├── domain/               ← Pure functions. No I/O, no env, no Date.now()
│   │   ├── conflict.ts
│   │   ├── sequence.ts
│   │   └── position.ts       ← Fractional indexing
│   ├── storage/              ← SQL against DO SQLite. No business rules
│   │   └── changes.ts
│   └── lib/                  ← Cross-cutting helpers
├── migrations/               ← [G1]
├── test/
├── biome.json
├── tsconfig.json
├── wrangler.jsonc
└── package.json
```

#### Rules

- **`domain/` MUST be pure.** No imports from `storage/`, no `env`, no `fetch`, no `Date.now()`,
  no `crypto.randomUUID()`. Time and identity are passed in as arguments. This is what makes
  the conflict rules testable without a runtime.
- `storage/` MUST NOT contain business rules. It reads and writes rows; it does not decide
  which write wins.
- `index.ts` MUST NOT contain business logic. It authenticates, resolves which DO to talk to,
  and forwards.

---

<a id="standard-d2"></a>

### D2 — TypeScript Configuration

**Requirement ID:** D2
**Applicability:** `server/`, `protocol/`.

#### Standard

`tsconfig.json` MUST set:

```jsonc
{
  "compilerOptions": {
    "strict": true,
    "noUncheckedIndexedAccess": true,
    "noImplicitOverride": true,
    "exactOptionalPropertyTypes": true,
    "verbatimModuleSyntax": true,
    "target": "es2022",
    "module": "es2022",
    "moduleResolution": "bundler",
    "lib": ["es2022"],
    "types": ["@cloudflare/workers-types"],
    "skipLibCheck": true
  }
}
```

`skipLibCheck` is required in `server/`, not a shortcut: `@cloudflare/workers-types` redeclares
DOM globals (`EventTarget`, `fetch`, etc.) in ways that conflict with unrelated `.d.ts` files
pulled in transitively by dev tooling (observed with `vitest`'s `tinybench` dependency).
Without it, `tsc --noEmit` fails inside `node_modules`, not in this repo's own code — exactly
what `skipLibCheck` exists for.

#### Rules

- `any` is a review blocker. Use `unknown` and narrow.
- `as` casts MUST carry a comment explaining why the compiler cannot know. A cast on data
  crossing the wire is always wrong — validate it instead ([F3]).
- `noUncheckedIndexedAccess` is deliberate: array access returns `T | undefined`, which forces
  the empty case to be handled. Do not disable it.

---

<a id="standard-d3"></a>

### D3 — Durable Object Rules

**Requirement ID:** D3 **(SYNC)**
**Applicability:** `server/src/do/`.

#### What

The Durable Object is the single-threaded owner of one list. Its guarantees are the reason
this architecture is simple; violating them silently gives them up.

#### Rules

- **One DO per list.** The DO id MUST derive from the list id via `idFromName(listId)`.
  Never a random id, never one DO for multiple lists.
- **Sequence numbers MUST be assigned inside the DO**, in the same turn as the change-row
  write. Never in the Worker, never derived from a timestamp.
- **All storage writes for one logical change MUST be in one transaction.** The change row and
  the state mutation land together or not at all.
- **No module-level mutable state.** A Worker isolate is shared across requests and across
  DOs; a module-scope `let` is a cross-tenant data leak. All state lives in `this` or storage.
- **WebSocket Hibernation API MUST be used** (`acceptWebSocket`, not `addEventListener`).
  Holding a non-hibernatable socket bills idle duration and breaks the free-tier economics.
- **`blockConcurrencyWhile` MUST wrap initialization** that later requests depend on.
- Server time MUST come from a single call per request, passed down. Do not call `Date.now()`
  in three places and assume they agree.

---

<a id="standard-d4"></a>

### D4 — Error Handling and Logging

**Requirement ID:** D4
**Applicability:** `server/`.

#### Rules

- Logs MUST be structured JSON via a single `log()` helper. No bare `console.log` in product
  code — it is unsearchable and cannot be sampled.
- A log line MUST NOT contain task titles, list names, or note bodies. Log ids, not content.
  This is a shopping list; it is still the user's data.
- Errors returned to a client MUST NOT include stack traces or internal messages. Return a
  stable machine-readable `code` and let the client decide the wording.
- An unhandled rejection in a DO MUST be caught at the boundary and logged, not left to
  reset the object silently.

---

## E: Kotlin & Android Standards

---

<a id="standard-e1"></a>

### E1 — Android Module Layout

**Requirement ID:** E1
**Applicability:** `android/`.

#### Standard

```
android/app/src/main/java/za/co/wonderlys/
├── ui/                       ← Compose only. No business logic, no I/O
│   ├── lists/
│   ├── tasks/
│   └── theme/
├── data/
│   ├── local/                ← Room: entities, DAOs, migrations
│   ├── remote/               ← WebSocket + HTTP clients
│   └── sync/                 ← Outbox drain, cursor, apply-change
├── domain/                   ← Pure Kotlin. MUST NOT import android.* or androidx.*
└── di/                       ← Hilt modules
```

Package name is `za.co.wonderlys`.

#### Rules

- **`domain/` MUST NOT import `android.*` or `androidx.*`.** Enforced by a Konsist test in CI,
  not by good intentions. This keeps the conflict-resolution logic unit-testable on the JVM
  with no emulator.
- **The UI layer MUST read only from Room**, never from the network. A Compose screen that
  calls a repository which calls the network is the bug this whole architecture exists to
  prevent.
- Room queries returning `Flow` are the only way the UI observes data. No manual refresh calls.
- `ViewModel` MUST NOT hold an Android `Context`.

---

<a id="standard-e2"></a>

### E2 — Kotlin Conventions

**Requirement ID:** E2
**Applicability:** `android/`.

#### Rules

- Follow the official Kotlin style guide; ktlint is the arbiter.
- Data crossing a layer boundary MUST be an immutable `data class`. No mutable shared models.
- Nullability MUST be modelled honestly. `!!` is a review blocker outside tests.
- Coroutines: no `GlobalScope`. Use `viewModelScope`, or `WorkManager` for anything that must
  survive process death — which includes every outbox drain.
- Blocking I/O MUST be on `Dispatchers.IO`. Room already enforces this for suspend DAOs; do
  not defeat it with `runBlocking`.

---

## F: The Sync Contract

---

<a id="standard-f1"></a>

### F1 — Protocol is the Single Source of Truth

**Requirement ID:** F1 **(SYNC)**
**Applicability:** `protocol/`.

#### What

Every message that crosses the wire MUST be defined in `protocol/src`, documented in
`protocol/PROTOCOL.md`, and exemplified in `protocol/fixtures/`.

#### Why

The server is TypeScript and the client is Kotlin. Nothing in either compiler stops them
disagreeing. The fixtures are the only mechanism that makes disagreement fail a build rather
than fail in a shop.

#### Rules

- A wire type MUST NOT be declared in `server/src` or in Kotlin without first existing in
  `protocol/`.
- Kotlin data classes mirroring protocol types MUST be verified against the fixtures by a
  test ([F4]). Hand-mirroring is allowed; unverified hand-mirroring is not.
- `protocol/` MUST have zero runtime dependencies.

---

<a id="standard-f2"></a>

### F2 — Protocol Versioning

**Requirement ID:** F2 **(SYNC)**
**Applicability:** `protocol/`.

#### Standard

- `PROTOCOL_VERSION` is a single integer, exported from `protocol/src/version.ts`.
- The client MUST send it in the WebSocket handshake.
- The server MUST reject an unknown-higher version with a distinct error code, and MUST
  continue to serve versions it still supports.
- **Additive changes** (new optional field, new message type) do not bump the version.
- **Breaking changes** (removed field, changed meaning, changed type) MUST bump it, and the
  PR MUST state the rollout plan.

#### Rules

- Old clients MUST be assumed to exist forever. A sideloaded APK on a phone in a drawer will
  reconnect one day. The server never assumes everyone upgraded.
- Unknown fields in a received message MUST be ignored, not rejected. Forward compatibility
  is what makes additive changes safe.

---

<a id="standard-f3"></a>

### F3 — Validation at the Boundary

**Requirement ID:** F3 **(SYNC)**
**Applicability:** `server/`, `android/`.

#### Rules

- Every inbound message MUST be validated against a schema before any field is read. A
  parsed JSON result is `unknown`, not a typed message.
- Validation failure MUST be logged with the message type and rejected with a stable code.
  It MUST NOT crash the DO or drop the WebSocket.
- The client MUST validate server messages too. A malformed change applied to local SQLite
  is a corrupted local database, which is worse than a dropped connection.

---

<a id="standard-f4"></a>

### F4 — Golden Fixtures

**Requirement ID:** F4 **(SYNC)**
**Applicability:** `protocol/fixtures/`.

#### What

A set of canonical JSON files, one per message type, plus edge cases: empty list, unicode
title, maximum lengths, tombstoned task, conflicting update pair.

#### Rules

- Both the TypeScript and the Kotlin test suites MUST parse every fixture and assert the
  resulting object round-trips byte-identically.
- A change to the wire format MUST update the fixtures in the same commit.
- Fixtures MUST NOT be generated by the code they test.

---

<a id="standard-f5"></a>

### F5 — Sync Invariants

**Requirement ID:** F5 **(SYNC)**
**Applicability:** Everywhere.

These are the rules the whole design rests on. Each MUST have a test ([H3]).

| ##   | Invariant                                                                                        |
|------|--------------------------------------------------------------------------------------------------|
| F5.1 | Entity ids are **client-generated UUIDv7**. The server never mints an id                         |
| F5.2 | Every mutation carries an **idempotency key**. Redelivery is a no-op returning the original result |
| F5.3 | Deletes are **tombstones** (`deleted_at`), never `DELETE`                                        |
| F5.4 | Conflict resolution is **per-field last-write-wins on server timestamp**, tie-broken by device id |
| F5.5 | Ordering uses **fractional indexing**. Positions are strings; reorder never renumbers siblings   |
| F5.6 | The client applies changes **in seq order**. A gap MUST trigger a catch-up pull, never a skip    |
| F5.7 | The local write and its outbox row MUST commit in **one local transaction**                      |
| F5.8 | The cursor MUST advance **only after** the change is committed locally                           |
| F5.9 | **Device clocks are never trusted** for ordering. Server timestamps only                         |

---

## G: Schema & Migrations

---

<a id="standard-g1"></a>

### G1 — Server Migrations (DO SQLite)

**Requirement ID:** G1
**Applicability:** `server/migrations/`.

#### Standard

```
server/migrations/
├── 0001_initial.sql
├── 0002_add_task_notes.sql
└── AGENTS.md
```

- Four-digit zero-padded sequence, underscore, snake_case description.
- Each DO tracks its applied version in a `_migrations` table in its own storage.
- Migrations run inside `blockConcurrencyWhile` on first access after deploy.

#### Rules

- Migrations MUST be forward-only and idempotent.
- A migration MUST NOT be edited once merged to `main`. Fix forward with a new file.
- A migration that drops or renames a column MUST be split across two releases: stop writing
  it, deploy, then drop it. Every DO migrates lazily on first touch, so both shapes coexist
  in production for as long as a list goes untouched — which can be months.
- Destructive migrations MUST state in the PR body what data is lost.

---

<a id="standard-g2"></a>

### G2 — Client Migrations (Room)

**Requirement ID:** G2
**Applicability:** `android/app/src/main/java/za/co/wonderlys/data/local/`.

#### Rules

- Every schema change MUST bump the Room version and supply an explicit `Migration`.
- `fallbackToDestructiveMigration()` is **forbidden in release builds**. It silently wipes
  the local database, which on this app means wiping an unsynced outbox — user data loss with
  no error message.
- Room's exported schema JSON MUST be committed. It is the only record of what shipped.
- Every migration MUST have a `MigrationTestHelper` test that populates the old schema,
  migrates, and asserts the data survived.

---

## H: Testing

---

<a id="standard-h1"></a>

### H1 — Test Stack

**Requirement ID:** H1
**Applicability:** All components.

| Component  | Framework                                     | Runs where                      |
|------------|-----------------------------------------------|---------------------------------|
| Server     | Vitest + `@cloudflare/vitest-pool-workers`    | Real `workerd`, real DO storage |
| Protocol   | Vitest                                        | Node                            |
| Android    | JUnit5 + Turbine + Room `MigrationTestHelper` | JVM, no emulator                |

#### Rules

- Server tests MUST run in `workerd` via the Workers vitest pool, not against a mock. A mocked
  Durable Object cannot reproduce the single-threading guarantee that the design depends on.
- Android tests MUST run on the JVM. If a test needs an emulator, the code under test has an
  Android dependency it should not have ([E1]).

---

<a id="standard-h2"></a>

### H2 — Coverage Requirements

**Requirement ID:** H2
**Applicability:** All components.

| Area                              | Requirement                         |
|-----------------------------------|-------------------------------------|
| `server/src/domain/`              | MUST — 90% line coverage            |
| `android/.../domain/`             | MUST — 90% line coverage            |
| `android/.../data/sync/`          | MUST — every scenario in [H3]       |
| Room migrations                   | MUST — one test per migration       |
| `server/src/do/`                  | SHOULD — integration tests          |
| Compose UI                        | MAY — no snapshot tests required    |

#### Rules

- Coverage is a floor on the pure layers only. Chasing a global percentage produces tests that
  assert nothing.
- A bug fix MUST add the test that would have caught it. This is not negotiable for anything
  tagged **(SYNC)**.

---

<a id="standard-h3"></a>

### H3 — The Offline Scenario Matrix

**Requirement ID:** H3 **(SYNC)**
**Applicability:** `android/.../data/sync/`, `server/src/do/`.

#### What

These scenarios MUST each have a named, automated test. They are the failure modes that
cannot be reproduced by hand once they reach a phone.

| ##    | Scenario                                    | Expected                                          |
|-------|---------------------------------------------|---------------------------------------------------|
| H3.1  | Write while offline, reconnect              | Outbox drains, change lands, cursor advances      |
| H3.2  | Same mutation delivered twice               | Idempotency key makes the second a no-op          |
| H3.3  | Server returns seq gap                      | Client pulls `?since=` and self-heals             |
| H3.4  | Process killed mid-drain                    | Outbox survives, replays on next launch           |
| H3.5  | Both devices tick the same task             | Converges, no error, no flicker                   |
| H3.6  | Both devices add a task while offline       | Both survive, distinct ids                        |
| H3.7  | Delete races an update                      | Tombstone wins; update does not resurrect the row |
| H3.8  | Device clock 10 minutes fast                | Server timestamp decides, client clock ignored    |
| H3.9  | Both devices reorder offline                | Fractional indices merge, no swap, no duplicate   |
| H3.10 | Response lost after server committed        | Retry returns the original result, no duplicate   |
| H3.11 | WebSocket dies silently mid-session         | Heartbeat detects, reconnect, catch-up pull       |
| H3.12 | Client on an old PROTOCOL_VERSION connects  | Served correctly, or rejected with a clear code   |

A PR that changes sync behaviour MUST state in **How to test** which of these it exercises.

---

## I: Secrets & Configuration

---

<a id="standard-i1"></a>

### I1 — Secret Storage

**Requirement ID:** I1
**Applicability:** All components.

#### Standard

| Secret                     | Local dev            | CI / Deployed                        |
|----------------------------|----------------------|--------------------------------------|
| JWT signing key            | `.dev.vars`          | `wrangler secret put`                |
| Cloudflare API token       | not needed           | GitHub environment secret            |
| Android signing keystore   | local file, ignored  | base64 GitHub environment secret     |
| FCM server credentials     | `.dev.vars`          | `wrangler secret put`                |

#### Rules

- **`wrangler.jsonc` `vars` are plaintext and are committed.** Only non-sensitive
  configuration goes there. A secret in `vars` is a secret in git.
- `.dev.vars` MUST be gitignored and MUST have a committed `.dev.vars.example` listing the
  required keys with dummy values.
- Secrets MUST NOT be passed as build arguments or printed in CI logs. GitHub masks known
  secrets; it cannot mask one you constructed by string concatenation.
- Rotation: any secret that has ever appeared in a commit, a log, or a screenshot MUST be
  rotated. Deleting the commit is not rotation.

---

<a id="standard-i2"></a>

### I2 — Configuration

**Requirement ID:** I2
**Applicability:** `server/`.

#### Rules

- Environment differences MUST be expressed as named environments in `wrangler.jsonc`, not
  as `if (env.ENVIRONMENT === "prod")` branches in product code.
- Configuration MUST be validated at startup. A missing binding should fail the first
  request loudly, not produce `undefined` three layers deep.

---

## J: CI/CD & Deployment

---

<a id="standard-j1"></a>

### J1 — Environments

**Requirement ID:** J1
**Applicability:** GitHub, Cloudflare.

#### Standard

| Environment | Worker name       | Trigger                  | Protection             |
|-------------|-------------------|--------------------------|------------------------|
| `dev`       | `wonderlys-dev`   | push to `main`           | none — auto-deploys    |
| `prod`      | `wonderlys-prod`  | tag `v*`                 | required reviewer      |

#### Rules

- Both environments MUST exist as GitHub Environments with their own secrets.
- `prod` MUST have a required-reviewer protection rule. On a solo project this is a
  deliberate speed bump before touching the database your household depends on. Self-approval
  is permitted — GitHub's *Prevent self-review* option stays off.
- Dev and prod MUST NOT share Durable Object namespaces. A dev deploy must be incapable of
  touching production lists.

#### Plan Dependency

> **This requirement depends on the repository staying public.** On GitHub Free, environments,
> environment secrets and deployment protection rules are available for **public** repositories
> only. Making this repo private on the Free plan silently drops the `prod` gate and the
> environment secrets it holds.
>
> If the repo is ever made private, one of the following MUST happen in the same change:
> upgrade the plan, or replace the gate with a manual `workflow_dispatch` trigger on the prod
> deploy so promotion is still a deliberate act. Flipping visibility without doing either is a
> silent loss of control, not a settings change.

---

<a id="standard-j2"></a>

### J2 — CI Workflow

**Requirement ID:** J2
**Applicability:** `.github/workflows/ci.yml`.

#### What

Runs on every push to `main` and every PR targeting `main`. MUST pass before merge.

#### Standard Job Structure

```yaml
name: CI
on:
  push: { branches: [main] }
  pull_request: { branches: [main] }

jobs:
  server:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with: { node-version: '22', cache: 'npm' }
      - run: npm ci
      - run: npx biome ci .          # format + lint, fails fast
      - run: npx tsc --noEmit        # typecheck
      - run: npm test                # vitest in workerd

  android:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: 'temurin', java-version: '21' }
      - uses: gradle/actions/setup-gradle@v4
      - run: ./gradlew ktlintCheck detekt testDebugUnitTest
        working-directory: android
```

#### Rules

- Jobs MUST be independent so a Kotlin failure does not mask a TypeScript failure.
- CI MUST NOT deploy. Deployment is a separate workflow with its own permissions.

---

<a id="standard-j3"></a>

### J3 — Server Deployment

**Requirement ID:** J3
**Applicability:** `.github/workflows/deploy-server.yml`.

#### Rules

- Deployment MUST use a scoped Cloudflare API token, not a global key. Minimum scope:
  Workers Scripts Edit on the one account.
- The workflow MUST re-run tests before deploying. Never deploy an artifact CI has not seen.
- Every deploy MUST be traceable to a commit SHA, recorded in the deploy summary.
- **Rollback** is `wrangler rollback` or redeploying the previous tag, and MUST be documented
  in `README.md`. A rollback path discovered during an incident is not a rollback path.
- Migrations run lazily per DO ([G1]), so a rollback of code does **not** roll back schema.
  A deploy containing a migration MUST be treated as forward-only.

---

<a id="standard-j4"></a>

### J4 — Android Release

**Requirement ID:** J4
**Applicability:** `.github/workflows/release-android.yml`.

#### Rules

- Tagging `v*` MUST build a signed release APK and attach it to a GitHub Release.
- The signing keystore MUST come from a GitHub environment secret, base64-decoded at build
  time and never written to a path that a later step archives.
- `versionCode` MUST increase monotonically. Derive it from the run number, not by hand.
- Losing the keystore means no upgrade path for installed apps. It MUST be backed up outside
  this repo, encrypted, before the first release.

---

## K: Documentation Standards

---

<a id="standard-k1"></a>

### K1 — `README.md` at Repo Root

**Requirement ID:** K1

#### Required Sections

```markdown
## Wonderlys

### Requirements
### Getting Started          ← clone to running locally, both halves
### Project Layout           ← one line per top-level folder
### Running Tests
### Deploying
### Rolling Back             ← [J3], exact commands
### Authors
```

The **Getting Started** section MUST be verified by following it on a clean machine whenever
setup changes. A README that has drifted is worse than none.

---

<a id="standard-k2"></a>

### K2 — `docs/SYNC.md`

**Requirement ID:** K2 **(SYNC)**

#### What

A prose explanation of how sync works: the local-first model, the outbox, the changelog, the
cursor, the reconnect path, and each invariant in [F5] with its rationale.

#### Why

The sync engine is the only genuinely hard part of this codebase. Six months from now the
reason a rule exists will not be obvious from the code, and the failure mode of guessing is
silent data loss.

#### Rules

- MUST be updated in the same PR as any change to an [F5] invariant.
- MUST explain *why*, not just *what*. The code already says what.

---

<a id="standard-k3"></a>

### K3 — Architecture Decision Records

**Requirement ID:** K3

#### What

`docs/adr/NNNN-short-title.md`, sequentially numbered, recording decisions that were not
obvious and would otherwise be re-litigated.

#### Standard Format

```markdown
# NNNN — Title

**Status:** Accepted | Superseded by [NNNN] | Rejected
**Date:** YYYY-MM-DD

## Context
## Decision
## Consequences
## Alternatives considered
```

#### When to Write One

- Choosing or replacing a platform, framework, or major dependency
- Any change to an [F5] sync invariant
- Adding a top-level folder
- Deliberately deviating from this standard

An ADR MUST NOT be edited after acceptance. Supersede it with a new one.

**Backfill required:** ADR 0001 records the Workers + Durable Objects choice over AWS
serverless and self-hosted alternatives, including the finding that Durable Objects do not
spawn in Africa. ADR 0002 records the authentication design ([L](#l-authentication)).

---

<a id="standard-k4"></a>

### K4 — `AGENTS.md`

**Requirement ID:** K4

#### Rules

- A root `AGENTS.md` MUST exist, describing the repo shape, the two-language split, and the
  rules an assistant most often gets wrong here (purity of `domain/`, protocol-first changes,
  never `fallbackToDestructiveMigration`).
- A folder-level `AGENTS.md` SHOULD exist wherever local context is non-obvious:
  `server/migrations/`, `protocol/`, `android/.../data/sync/`, `scripts/`.
- `AGENTS.md` MUST be updated when the convention it describes changes. A stale one actively
  misleads.

---

<a id="standard-k5"></a>

### K5 — `CLAUDE.md`

**Requirement ID:** K5

#### Standard Format

```markdown
# CLAUDE.md

See [AGENTS.md](AGENTS.md).

Index of folder-level notes:
- [server/migrations/AGENTS.md](server/migrations/AGENTS.md)
- [protocol/AGENTS.md](protocol/AGENTS.md)
```

A redirect and an index. Content lives in `AGENTS.md`, never duplicated here.

---

## L: Authentication

---

<a id="standard-l1"></a>

### L1 — Token Issuance and Storage

**Requirement ID:** L1
**Applicability:** `server/src/auth/`, `android/.../data/remote/`.

#### What

Full rationale in [ADR 0002](adr/0002-authentication.md). This section is the enforceable
summary.

#### Standard

- A single global Durable Object, `UsersRoom` (`idFromName("users-v1")`), holds accounts,
  device-scoped refresh tokens, and list membership. Same DO-embedded SQLite pattern as a
  `ListRoom`, its own migrations under [G1](#standard-g1).
- Passwords MUST be hashed with PBKDF2-SHA256 via `crypto.subtle`, random per-user salt, no
  plaintext or reversibly-encrypted password ever stored.
- **Access token:** JWT, HS256, signed with the `JWT_SIGNING_KEY` secret. Claims: `sub`,
  `deviceId`, `iat`, `exp`. TTL 15 minutes.
- **Refresh token:** random 256-bit value, stored only as its SHA-256 hash, scoped to one
  `deviceId`, TTL 30 days, rotated on every use — the previous value is invalidated the moment
  a new one is issued.
- `server/src/auth/jwt.ts` verifies tokens only. It MUST NOT touch storage
  ([D1](#standard-d1)).

#### Rules

- The Worker MUST verify the access token on every request before resolving which DO to talk
  to.
- A refresh token presented twice (reuse after rotation) MUST be treated as a compromise
  signal: reject the request and invalidate every refresh token for that user.
- `deviceId` MUST be the same identifier used for [F5.4](#standard-f5) LWW tie-breaks.
  Introduce it once.

---

<a id="standard-l2"></a>

### L2 — Registration

**Requirement ID:** L2
**Applicability:** `server/`, `scripts/`.

#### Standard

There is no public registration endpoint. Accounts are created by `scripts/create-user.ts`,
following [A2](#standard-a2): it MUST print what it is about to do and prompt for confirmation
before writing to `UsersRoom`.

#### Rules

- A public `/auth/register` endpoint is a review blocker unless a new ADR supersedes
  [ADR 0002](adr/0002-authentication.md) with a reason the user base is expected to grow.

---

<a id="standard-l3"></a>

### L3 — List Invites

**Requirement ID:** L3 **(SYNC)**
**Applicability:** `server/src/do/`, `server/src/auth/`.

#### What

How a second user gets access to a list.

#### Standard

- The list owner requests an invite; the Worker mints a short-lived (7-day) JWT scoped to one
  `listId`, distinct in claim shape from an access token so it cannot be replayed as one.
- The invitee, authenticated as themselves, POSTs the invite token to an accept endpoint. The
  Worker verifies it and writes a membership row (`userId`, `listId`, `role`) to `UsersRoom`.
- **`ListRoom` MUST NOT check membership itself.** Every request to a `ListRoom` MUST be
  authorized by the Worker against `UsersRoom` membership first. Authorization logic lives in
  exactly one place.

#### Rules

- An invite token MUST NOT be accepted twice for the same `(userId, listId)` pair in a way
  that creates duplicate membership rows — accepting an already-accepted invite is a no-op,
  not an error, matching the idempotency spirit of [F5.2](#standard-f5).

---

## M: Push Notifications (FCM)

---

<a id="standard-m1"></a>

### M1 — Purpose and Payload Contract

**Requirement ID:** M1 **(SYNC)**
**Applicability:** `server/`, `android/.../data/remote/`.

#### What

FCM exists for exactly one reason: wake a backgrounded Android app so its outbox-drain and
catch-up pull can run. It is not a notification system and it is not a transport for data.

#### Rules

- A push sent to wake a device MUST be a **data-only message**, never an FCM `notification`
  payload. Payload is limited to `{ "type": "sync", "listId": "<id>", "seq": <n> }`.
- A push message MUST NOT carry a task title, list name, note body, or any other user content.
  This mirrors the logging rule in [D4](#standard-d4) — FCM's servers are as much "outside this
  system" as a log aggregator is.
- On receipt, the client MUST treat the payload as a hint to sync, not as data to display.
  Any user-visible notification (e.g. "3 new items on Groceries") MUST be composed
  client-side, after sync, from local Room data — never from the push payload directly. This
  is the same rule as [E1](#standard-e1): the UI layer reads from Room, not from the network.

---

<a id="standard-m2"></a>

### M2 — Device Tokens, Not Topics

**Requirement ID:** M2
**Applicability:** `server/`, `android/`.

#### What

FCM supports both per-device registration tokens and topic subscriptions (broadcast to every
subscriber). Wonderlys uses device tokens only.

#### Why

A topic wakes every device subscribed to it indiscriminately. A list's membership is exactly
the set of devices that should be woken for a change to that list, and that set is already
tracked in `UsersRoom` ([L1](#standard-l1)) — reusing it means one source of truth for "who
sees this list" instead of two (membership rows and topic subscriptions) that can drift.

#### Standard

- The FCM token is stored in `UsersRoom`, keyed by `deviceId`, alongside the refresh token row
  for that device.
- **Token refresh:** the Android `FirebaseMessagingService.onNewToken` callback MUST send the
  new token to the server via an authenticated request. A stale token left unsent means that
  device silently stops receiving wake pushes.
- On a `ListRoom` write, the DO determines which member devices (via `UsersRoom` membership)
  do not have an active hibernating WebSocket, and sends each one a data message via the FCM
  HTTP v1 API using `fetch`.
- A device with an active WebSocket connection MUST NOT also receive an FCM push for the same
  change — the socket already delivered it. Sending both is not incorrect (the client
  catch-up pull is idempotent per [F5.6](#standard-f5)) but is wasted FCM quota and MUST be
  avoided.

---

## N: Dependency Policy

---

<a id="standard-n1"></a>

### N1 — Adding a Dependency

**Requirement ID:** N1
**Applicability:** `server/`, `protocol/`, `android/`.

#### Rules

- `protocol/` MUST NOT gain any dependency, runtime or dev, beyond TypeScript and the test
  runner ([F1](#standard-f1) already requires zero *runtime* deps; this extends the same
  discipline to dev deps, so the contract stays trivially auditable by anyone, on either side
  of the wire, with no build step to trust).
- A new dependency in `server/` or `android/` MUST be justified in the PR description: what it
  replaces, and why the equivalent is not a reasonable amount of code to own directly.
- A candidate dependency for `server/` MUST be checked for `workerd` compatibility before it is
  added — many npm packages assume Node APIs (`fs`, `net`, native bindings) that do not exist
  in the Workers runtime. Finding this out at deploy time instead of at review time is a
  wasted cycle.
- A dependency with no commit in the last 12 months is not automatically disqualified, but
  MUST be called out in the PR — an abandoned dependency is a future security patch nobody is
  writing.

---

<a id="standard-n2"></a>

### N2 — Automated Updates

**Requirement ID:** N2
**Applicability:** Repo root.

#### Standard

Dependabot, not Renovate — it is native to GitHub (no separate app install), free on a public
repo, and covers every ecosystem this repo needs (`npm` for `server/`/`protocol/`/root,
`gradle` for `android/`, `github-actions` for the workflows) from one config file,
`.github/dependabot.yml`, on a weekly schedule.

#### Rules

- A Dependabot PR that only bumps a version with no changelog risk (patch releases of a
  well-behaved package) MAY be merged on CI passing alone.
- A Dependabot PR bumping a major version MUST be reviewed as carefully as a hand-written
  dependency change against [N1](#standard-n1).

---

## Appendix A: Compliance Checklist

Use to verify the repo before first release, and to audit periodically.

**Instructions:** Mark Pass / Fail / N/A. For each Fail, open an issue.

### Repository Details

| Field      | Value |
|------------|-------|
| Audit date |       |
| Auditor    |       |
| Commit SHA |       |

### A: Evaluate Repository Structure

| ##   | Requirement                                        | Status |
|------|----------------------------------------------------|--------|
| A1.1 | All root folders from [A1] exist                   |        |
| A1.2 | `protocol/` imports nothing from server or android |        |
| A2.1 | `scripts/` exists with `AGENTS.md`                 |        |

### B: Evaluate Code Style & Formatting

| ##   | Requirement                                        | Status |
|------|----------------------------------------------------|--------|
| B1.1 | `.editorconfig` at root, matches [B1]              |        |
| B2.1 | `biome.json` present, no competing Prettier config |        |
| B2.2 | ktlint and detekt configured                       |        |
| B3.1 | CI runs format check before build and test         |        |

### C: Evaluate Source Control

| ##   | Requirement                                        | Status |
|------|----------------------------------------------------|--------|
| C1.1 | `.gitignore` covers every pattern in [C1]          |        |
| C1.2 | `git ls-files` shows no `.dev.vars`, no `*.jks`    |        |
| C2.1 | Recent commits follow `[#NN]` format               |        |
| C3.1 | `main` protected, linear history, CI required      |        |
| C4.1 | PR template present with Protocol impact section   |        |

### D/E: Evaluate Language Standards

| ##   | Requirement                                        | Status |
|------|----------------------------------------------------|--------|
| D2.1 | `tsconfig.json` strict flags per [D2]              |        |
| D2.2 | No `any` in `server/src`                           |        |
| D3.1 | DO id derived via `idFromName(listId)`             |        |
| D3.2 | WebSocket Hibernation API in use                   |        |
| D3.3 | No module-level mutable state                      |        |
| D4.1 | No task titles or note bodies in logs              |        |
| E1.1 | Konsist test enforces `domain/` has no `android.*` |        |
| E1.2 | UI reads only from Room                            |        |

### F: Evaluate Sync Contract

| ##   | Requirement                                        | Status |
|------|----------------------------------------------------|--------|
| F1.1 | Every wire type declared in `protocol/`            |        |
| F2.1 | `PROTOCOL_VERSION` sent in handshake and checked   |        |
| F3.1 | All inbound messages schema-validated              |        |
| F4.1 | Fixtures parsed by both TS and Kotlin test suites  |        |
| F5.1 | All nine invariants hold and are tested            |        |

### G: Evaluate Migrations

| ##   | Requirement                                        | Status |
|------|----------------------------------------------------|--------|
| G1.1 | Migrations sequential, forward-only, unedited      |        |
| G2.1 | No `fallbackToDestructiveMigration` in release     |        |
| G2.2 | Room schema JSON committed                         |        |
| G2.3 | Every Room migration has a test                    |        |

### H: Evaluate Testing

| ##   | Requirement                                        | Status |
|------|----------------------------------------------------|--------|
| H1.1 | Server tests run in `workerd`                      |        |
| H2.1 | `domain/` coverage at or above 90% both sides      |        |
| H3.1 | All twelve H3 scenarios have named tests           |        |

### I: Evaluate Secrets

| ##   | Requirement                                        | Status |
|------|----------------------------------------------------|--------|
| I1.1 | No secrets in `wrangler.jsonc` vars                |        |
| I1.2 | `.dev.vars.example` present and current            |        |
| I1.3 | Keystore backed up encrypted outside the repo      |        |

### J: Evaluate CI/CD

| ##   | Requirement                                        | Status |
|------|----------------------------------------------------|--------|
| J1.1 | `dev` and `prod` environments exist and are split  |        |
| J1.2 | `prod` has a required reviewer                     |        |
| J1.3 | Repo is public, or the prod gate has been replaced |        |
| J3.1 | Scoped API token, not a global key                 |        |
| J3.2 | Rollback documented in README                      |        |
| J4.1 | `versionCode` derived, monotonic                   |        |

### K: Evaluate Documentation

| ##   | Requirement                                        | Status |
|------|----------------------------------------------------|--------|
| K1.1 | README verified on a clean machine                 |        |
| K2.1 | `docs/SYNC.md` current with [F5]                   |        |
| K3.1 | ADR 0001 backfilled                                |        |
| K4.1 | Root `AGENTS.md` present and current               |        |

### L: Evaluate Authentication

| ##   | Requirement                                        | Status |
|------|-----------------------------------------------------|--------|
| L1.1 | Passwords hashed with PBKDF2-SHA256, never plaintext |      |
| L1.2 | Refresh tokens stored only as a hash, rotated on use |      |
| L2.1 | No public `/auth/register` endpoint                |        |
| L3.1 | `ListRoom` never checks membership itself           |        |

### M: Evaluate Push Notifications

| ##   | Requirement                                        | Status |
|------|-----------------------------------------------------|--------|
| M1.1 | FCM payloads are data-only, no notification payload |       |
| M1.2 | No task/list content in any FCM payload            |        |
| M2.1 | Device tokens used, no topic subscriptions          |        |

### N: Evaluate Dependency Policy

| ##   | Requirement                                        | Status |
|------|-----------------------------------------------------|--------|
| N1.1 | `protocol/` has zero runtime and dev dependencies  |        |
| N2.1 | `.github/dependabot.yml` present, covers all ecosystems |   |

### Scoring

| Score           | Meaning                                     |
|-----------------|---------------------------------------------|
| 0 Fails         | Compliant                                   |
| 1–5 Fails       | Minor gaps — open issues                    |
| 6+ Fails        | Stop feature work, remediate first          |
| Any (SYNC) Fail | Blocker regardless of total score           |

---

## Appendix B: Reference Templates

Template files live in `templates/` at the repo root once scaffolded.

| ## | Template                           | Defined in |
|----|------------------------------------|------------|
| 1  | `.editorconfig`                    | [B1]       |
| 2  | `.gitignore`                       | [C1]       |
| 3  | `.github/pull_request_template.md` | [C4]       |
| 4  | `tsconfig.json`                    | [D2]       |
| 5  | `.github/workflows/ci.yml`         | [J2]       |
| 6  | ADR skeleton                       | [K3]       |
| 7  | `CLAUDE.md`                        | [K5]       |
| 8  | `.dev.vars.example`                | [I1]       |

---

*Standard drafted 2026-09-08. Modelled on the Incon Backend Development Coding Standard,
adapted for TypeScript/Workers and Kotlin/Android. Requirements tagged **(SYNC)** protect
correctness of offline replication and MUST NOT be relaxed without an ADR.*
