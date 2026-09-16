# 0009 — A one-time ticket authenticates the web client's WebSocket

**Status:** Accepted
**Date:** 2026-09-16
**Builds on:** [ADR 0002](0002-authentication.md) and [L1/L3](../CODE_STANDARD.md#standard-l1).
Adds a second way to authenticate the WebSocket upgrade; does not change how any other route
authenticates, and does not touch L3's rule that `ListRoom` never checks membership itself.

## Context

Every request carries the access token as `Authorization: Bearer`, **including the WebSocket
upgrade** — L1 requires this because L3 puts authorization in the Worker, before anything reaches
a `ListRoom`, and the Worker never sees a frame sent after the socket is established. OkHttp lets
the Android client set that header on an upgrade request. A browser's `WebSocket` constructor
cannot set arbitrary headers on any request, upgrade included — this has been a known gap since
`docs/PLAN.md`'s "Web client" open question and is called out explicitly in `PROTOCOL.md`'s
Authentication section. `web/` cannot open a live socket without an answer.

`changes`/`mutate` do not have this problem: a browser's `fetch` can set `Authorization` like any
other client, so those two keep working exactly as they do today. Only the upgrade is special.

## Decision

**A short-lived, single-use ticket, minted over an already-authenticated request and redeemed once
on the upgrade's query string.**

- `POST /auth/ws-ticket` (bearer-required, no body) mints one. `UsersRoom` stores only its
  SHA-256 hash — the same shape as a refresh token (L1) — bound to the caller's `userId` and
  `deviceId`, with a 60-second TTL (`WS_TICKET_TTL_MS`).
- The browser opens `GET /lists/{listId}/ws?ticket=...` instead of setting a header. The Worker's
  `authorizeListAccess` tries `Authorization` first, and only falls back to `?ticket=` when there
  is no header — so Android's path is untouched, byte for byte.
- Redemption (`UsersRoom.redeemWsTicket`) marks the row used and refuses a second presentation.
  Unlike a refresh token, reuse is not treated as a compromise signal that revokes every session:
  the value's whole job is to cross the wire once, in a URL, and a stolen one is a stolen minute,
  not a stolen 30-day session.
- Whichever path authenticated the caller, the *same* membership check against `UsersRoom` runs
  before the request reaches a `ListRoom` — L3 holds identically for both. The `changes`/`mutate`
  actions never accept a ticket; only `ws` does.
- The ticket's bound `deviceId` is trusted over whatever `?deviceId=` the client put on the URL
  itself when a ticket authenticated the request — the Worker overwrites it before forwarding to
  the room, the same way it already overwrites `?role=` ([ADR 0006](0006-owner-only-list-delete.md)).

## Consequences

- One new table (`ws_tickets`), one new route, one new rate-limit bucket
  (`WS_TICKET_MINT_PER_CLIENT`, a volumetric backstop like `REFRESH_PER_CLIENT` — the ticket
  itself is 256 random bits, so this is not a guessing defence). No protocol version bump: it is
  an additive HTTP route, not a change to the WebSocket handshake or any message shape.
- The access token itself never appears in a URL, a browser's history, or a `Referer` header —
  only a 60-second single-use value does, and it is useless a minute after mint whether or not it
  was ever redeemed.
- A client now mints a ticket on every socket (re)connect rather than reusing one bearer token
  across many upgrades. Fine for the web client's connection cadence; would not be if a client
  reconnected far more often than the mint rate limit allows, which none does today.

## Alternatives considered

- **`Sec-WebSocket-Protocol` as a token carrier.** A browser *can* set this on the `WebSocket`
  constructor's `protocols` argument, unlike `Authorization`. Rejected: it overloads a
  protocol-negotiation field for something it was not designed to carry, and — being a header —
  it is just as likely to end up in an intermediate proxy or CDN's access log as `Authorization`
  would be, which defeats the point of avoiding a header in the first place.
- **The access token itself as `?token=`.** Simpler — no new endpoint, no new table — but wrong:
  a 15-minute bearer credential does not belong anywhere a browser keeps history, and a URL is
  exactly that. A ticket is fit for a query string only because it is single-use and short enough
  that "sat in a log" and "sat in history" are both harmless.
- **No WebSocket for `web/` at all, HTTP polling only.** Avoids the whole problem, but gives up
  the latency path entirely — every list update would wait for the next poll rather than arriving
  as it happens, which is most of what a socket is for (PROTOCOL.md "Transport").
