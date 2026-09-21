# web/

The web client: React + Vite + TypeScript, local-first like Android — an IndexedDB replica and
an outbox, so taps land instantly and work offline (see `AGENTS.md` and ADR 0011). Full feature
parity with Android: sign-in, lists, tasks, sharing, and account settings.

## Running it locally

Needs a `server/` running to talk to — see the root `README.md`'s "Local development" section
to get `wrangler dev` up on `http://127.0.0.1:8787` first.

```bash
cd web
npm ci
npm run dev
```

Serves on `http://localhost:5173` (Vite's default), pointed at `http://localhost:8787` unless
`VITE_API_BASE_URL` says otherwise (`src/api/client.ts`).

## Testing and formatting

```bash
npm test    # vitest run
npm run fix # biome check --write .
npx tsc --noEmit
```

Or all three, plus every other package, from the repo root: `scripts/verify.sh web`.

## Where to look next

`AGENTS.md` — the architecture: the local replica and outbox, how the WebSocket auth problem
(a browser cannot set headers on the upgrade) is solved, and how sync, sharing, and account
deletion are structured.
