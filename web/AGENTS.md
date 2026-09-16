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
  (H2) is a floor on pure logic only — anything ported from `server/src/domain/` (the
  fractional-index code in `src/domain/position.ts`, and `src/domain/uuid7.ts`, since no
  existing TS implementation existed to port) still needs the fixture-verified test the server
  has, and does (`src/domain/position.test.ts` against `protocol/fixtures/positions.json`).
- Guest/offline mode (`HANDOFF-67-guest-mode.md`, Android-only, issue #67) is unrelated to this
  and not in scope here.
- `localStorage` is where every *local, per-browser* preference lives instead — accent colour
  (`src/domain/accentStore.ts`), the Done section's expanded state, new-item placement
  (`src/domain/uiPrefs.ts`), a tapped invite arriving before sign-in (`src/domain/pendingInvite.ts`),
  and the refresh token itself (`src/auth/SessionContext.tsx`). None of it syncs, on the same
  reasoning Android keeps these out of Room: two people on one shared list may set them
  differently, and a colour or a placement choice is not something the other person needs to see.

## The WebSocket problem and its answer

A browser cannot set `Authorization` on a WebSocket upgrade the way Android's OkHttp can. This
client mints a one-time ticket first — `POST /auth/ws-ticket` — and opens the socket with
`?ticket=...` instead of a header. Full reasoning in
[ADR 0009](../docs/adr/0009-web-websocket-ticket-auth.md). `changes`/`mutate` still use a normal
`Authorization` header like any other `fetch` call; only the upgrade needed a second path.

## Lists and tasks: catch-up plus a live socket, no local write-ahead

`src/sync/useListsOverview.ts` and `src/sync/useTaskBoard.ts` are the two hooks that stand in for
`android/.../DielysRepository.kt` and its outbox. There is no optimistic local write: every
mutation `await`s the server's ack before the screen updates (a tap shows up a round trip later,
not immediately — the online-first trade-off spelled out above), and every list open re-runs a
full `GET .../changes` catch-up rather than trusting anything left over from a previous visit.
A list's title is genuinely unknown until its own changelog answers at least once — mirrors
Android's `hasArrived()` gate (`ListsViewModel.kt`) — so `ListRow.title` is `string | null`, not
defaulted to `""`.

## Sharing and account deletion

`src/sync/useSharing.ts` combines what `android/.../MembersViewModel.kt` and
`ListsViewModel`'s invite half do separately — one hook, since there is no dependency injection
here to split them across. Both halves are read live from the server on every open, never
cached, same reasoning as Android's: a stale answer about who can see a list is worse than a
beat of "checking…". An invite tapped while signed out is stashed in `sessionStorage`
(`src/domain/pendingInvite.ts`) and resumed once sign-in completes — `localStorage`/
`sessionStorage` is per-origin, not per-tab, so this survives a magic link opening in a new tab.

Account deletion has two independent paths, both hitting the same server-side erasure
(`DELETE /account`, ADR 0007): `src/pages/SettingsPage.tsx` for a signed-in user, and
`src/pages/DeleteAccountPage.tsx` for `dielys.com/account/delete[/confirm]` — the public,
no-session pages Google Play's account-deletion policy requires. Both of those routes are
checked in `App.tsx` *ahead of* the sign-in gate, the same way `/magic` and `/invite` are:
reaching them at all means there is no reason to expect a session.

## No navigation library

`src/router.tsx` is a few lines of `history.pushState` plus a `popstate` listener — the same call
Android's Compose UI already made ("no navigation library — the back stack is one nullable list
id", `docs/PLAN.md`). A handful of routes (`/`, `/magic`, `/invite`, `/settings`, list detail,
`/account/delete`, `/account/delete/confirm`) do not earn a routing dependency (N1).

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
`src/auth/SessionContext.tsx` also remembers the typed email there, best-effort — the server
never hands it back (a `TokenPair` carries only a user id), the same gap Android's
`SessionStore.kt` has, solved the same way: capture it when it's typed, not when it's needed.

## Theme

`src/theme.css` ports the Navy/Sand/Amber palette from `android/.../ui/theme/Theme.kt` verbatim —
not user-configurable, on the same reasoning Android's theme gives for skipping dynamic/Material
You color: two people on one household list should see the same app.

## Deployment

There is no separate hosting for `web/` — `scripts/build-web-public.sh` builds it and merges the
output into `server/public/` (alongside `server/public-static/`, the Play-required pages that stay
untouched — ADR 0007), and `server/`'s Worker deploy ships both together (`.github/workflows/
deploy-server.yml`). Same-origin with the API in every real deployment, which is why
`VITE_API_BASE_URL` builds empty there (`src/api/client.ts`) — only `npm run dev`'s Vite server
needs it set to reach a deployed backend across origins. `server/src/index.ts` serves this SPA's
shell (`env.ASSETS.fetch`) for any GET it does not otherwise recognise — `/magic`, `/invite`,
`/settings`, `/lists/{id}`, anything — after the assets layer already tried a real static file
first (`server/wrangler.jsonc`).

## Where to look next

- Tracking issue: #74 — all six build phases are done (auth, scaffold, lists/tasks, sharing,
  settings/account, this doc pass), deployed to dielys.com.
- `docs/adr/0009-web-websocket-ticket-auth.md` — the WebSocket auth decision.
- `docs/adr/0007-account-deletion.md` — why account deletion has two paths and erases rather
  than tombstones.
- `protocol/PROTOCOL.md` — the wire contract this client and `server/` both implement.
