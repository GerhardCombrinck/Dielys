# web/AGENTS.md

React + Vite + TypeScript, consuming `protocol/` the same way `server/` does — a plain
`"@dielys/protocol": "file:../protocol"` dependency, no workspace tooling (there is no root
`package.json`).

## Online-first, not offline-first

Unlike `android/`, this client has **no local replica and no outbox**. It talks to the server
directly (`src/api/`); a mutation is a direct `POST /lists/{id}/mutate` (or over the socket), and
a network failure surfaces as an error for the UI to show, not something queued for later. This
was a deliberate scope decision, not an oversight — see the tracking issue (#74). It means:

- The F5 sync invariants still apply to what's on screen (seq order, catch-up on a gap), but
  there is no persistence across a reload to protect: each load re-fetches full state and starts
  a fresh in-memory cursor.
- There is no Room-equivalent, no `data/local/`, no `MigrationTestHelper`-style test. Coverage
  (H2) is a floor on pure logic only — anything ported from `server/src/domain/` (e.g. the
  fractional-index code, when it lands) still needs the fixture-verified test the server has.
- Guest/offline mode (`HANDOFF-67-guest-mode.md`, Android-only, issue #67) is unrelated to this
  and not in scope here.

## The WebSocket problem and its answer

A browser cannot set `Authorization` on a WebSocket upgrade the way Android's OkHttp can. This
client mints a one-time ticket first — `POST /auth/ws-ticket` — and opens the socket with
`?ticket=...` instead of a header. Full reasoning in
[ADR 0009](../docs/adr/0009-web-websocket-ticket-auth.md). `changes`/`mutate` still use a normal
`Authorization` header like any other `fetch` call; only the upgrade needed a second path.

## No navigation library

`src/router.tsx` is a few lines of `history.pushState` plus a `popstate` listener — the same call
Android's Compose UI already made ("no navigation library — the back stack is one nullable list
id", `docs/PLAN.md`). A handful of routes (`/`, `/magic`, `/invite`, list detail, settings) do not
earn a routing dependency (N1).

## Auth: magic-link + code only

No password field anywhere, matching ADR 0005/0008. `src/pages/SignInPage.tsx` mirrors
`android/.../ui/auth/AuthScreen.kt`'s state machine (email → "check your email" with a code field
shown alongside it, not a separate step). The magic link itself is a real `https://dielys.com/magic`
URL, so there is no App-Links-style deep-link setup to port — `src/pages/MagicLinkPage.tsx` just
reads the `token` query param.

## Session storage

The access token lives in memory only (`src/api/client.ts`); a page reload always goes through one
refresh rather than trusting a stored access token. The refresh token is the one thing kept in
`localStorage`, keyed by this browser's device id (`src/domain/deviceId.ts`, generated once and
reused — it is what a refresh token is scoped to and what F5.4's LWW tie-break uses).

## Theme

`src/theme.css` ports the Navy/Sand/Amber palette from `android/.../ui/theme/Theme.kt` verbatim —
not user-configurable, on the same reasoning Android's theme gives for skipping dynamic/Material
You color: two people on one household list should see the same app.

## Where to look next

- Tracking issue: #74 — phases and what's left.
- `docs/adr/0009-web-websocket-ticket-auth.md` — the WebSocket auth decision.
- `protocol/PROTOCOL.md` — the wire contract this client and `server/` both implement.
