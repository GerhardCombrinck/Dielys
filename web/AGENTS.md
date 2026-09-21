# web/AGENTS.md

React + Vite + TypeScript, consuming `protocol/` the same way `server/` does — a plain
`"@dielys/protocol": "file:../protocol"` dependency, no workspace tooling (there is no root
`package.json`).

## Local-first, like Android

Same model as `android/` ([docs/SYNC.md](../docs/SYNC.md),
[ADR 0011](../docs/adr/0011-web-local-first.md)): the UI reads a **local replica**, every tap is
one local write plus an **outbox** row, and a sync engine makes the server agree afterwards.
Nothing on screen waits on the network. It was online-first until ADR 0011 (issue #74's first
cut) and felt slow for exactly that reason — don't reintroduce an `await` on the server between
a tap and the screen.

`src/data/` is the port, file-for-file where it can be:

| web | Android |
| --- | --- |
| `replica.ts` — lists, tasks, cursors, outbox; `apply()` | Room tables + `ChangeApplier.kt` |
| `idb.ts` — IndexedDB persistence, one transaction per write | Room / `withTransaction` |
| `repository.ts` — every user action as a local commit | `DielysRepository.kt` |
| `syncEngine.ts` — drain, memberships, catch-up, backoff | `SyncEngine.kt` + `WorkManager` |
| `store.tsx` — per-account lifecycle, React bindings | Hilt wiring |

The rules in `android/.../data/sync/AGENTS.md` apply here unchanged:

- An entity and its outbox row commit in **one** `Replica.transact` (F5.7) — `commit()` is the
  only way a local edit is written, so no caller can split them.
- The cursor moves only in the same write as the change it covers (F5.8). Socket pushes,
  catch-up pages and mutation acks all go through `Replica.apply` (F5.6); a `gap` pulls
  `?since=cursor` over HTTP, never skips.
- The idempotency key is minted once, in `repository.ts`, and the stored body is resent
  byte-for-byte on every retry (F5.2).
- Mutations go out over HTTP only. The socket is a latency optimisation; everything must still
  converge with it never connecting.
- A 4xx other than 401/408/429 parks a row as dead (`Replica.markDead`) — never delete what the
  user typed.
- **IndexedDB schema changes are numbered `onupgradeneeded` steps that keep existing data.**
  Never delete and recreate a store: the outbox is in there (the G2 rule, web edition).

Web-specific differences, all in ADR 0011: no background worker (a tab drains on open, commit,
focus, `online`, and on backoff — a closed tab's queue goes out on its next open); tabs share one
database, with a `BroadcastChannel` reload after each write and a Web Lock around sync; the first
sync per page load pulls every list instead of Android's daily sweep; sign-out erases the replica,
and data belonging to another user id is wiped on open.

Tests: `src/data/replica.test.ts` (the F5 rules) and `src/data/syncEngine.test.ts` (the H3
scenarios this client can reach, against a fake server with a real changelog). Pure logic also
keeps its fixture-verified tests (`src/domain/position.test.ts` against
`protocol/fixtures/positions.json`).

Guest/offline mode (`HANDOFF-67-guest-mode.md`, Android-only, issue #67) is unrelated to this
and not in scope here.

`localStorage` is where every *local, per-browser* preference lives, not the replica — accent
colour (`src/domain/accentStore.ts`), the Done section's expanded state, new-item placement
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

## Lists and tasks: two hooks over the replica

`src/sync/useListsOverview.ts` and `src/sync/useTaskBoard.ts` read the replica through
`useSyncExternalStore` and write through `Repository`; their methods still return promises so
the pages' `await`s keep compiling, but they resolve in the same tick. `useTaskBoard` also runs
the list's catch-up and live socket while the page is open — held back until a freshly created
list's claim has landed, since before then the server has no changelog to give. A list's title is
genuinely unknown until its own changelog answers at least once — mirrors Android's
`hasArrived()` gate (`ListsViewModel.kt`) — so `ListRow.title` is `string | null`, not defaulted
to `""`.

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
refresh rather than trusting a stored access token. The refresh token is kept in `localStorage`,
keyed by this browser's device id (`src/domain/deviceId.ts`, generated once and reused — it is
what a refresh token is scoped to and what F5.4's LWW tie-break uses), alongside the user id it
belongs to. With both on hand, a reload is signed in *before* that refresh answers, so the lists
paint from the replica straight away, offline included (ADR 0011); only a refresh the server
actually rejects signs the browser out (#83).
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
