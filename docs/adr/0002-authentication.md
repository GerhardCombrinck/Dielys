# 0002 — Authentication: self-issued JWT, admin-seeded accounts, signed invite links

**Status:** Accepted; the registration decision superseded by
[ADR 0004](0004-open-registration.md)
**Date:** 2026-09-08

## Context

Dielys has exactly two users. The earlier plan (ASP.NET Identity) died with the .NET
backend plan; the current stack is Cloudflare Workers, which has no built-in identity system.
[Gerhard owns his infra](../../CLAUDE.md) — Firebase Auth, Supabase Auth, and Clerk-style
third-party identity are rejected for the same reason Firebase/Supabase were rejected as a
backend in [ADR 0001](0001-workers-and-durable-objects.md): the household wants to own its
data flow, not hand credential storage and session issuance to a platform.

The standard needs a concrete answer to three questions before it can call authentication
settled:

1. Who issues tokens, and how does a client prove it's still logged in?
2. How does a new device or a token refresh work without re-entering a password constantly?
3. How does a second user get access to a list the first user created?

## Decision

### Storage

A single global Durable Object, `UsersRoom` (`idFromName("users-v1")`), holds user accounts,
device-scoped refresh tokens, and list membership, using the same DO-embedded SQLite pattern
as a `ListRoom` — no new storage primitive, no D1, no external database.

### Passwords

Passwords are hashed with PBKDF2-SHA256 (high iteration count, random per-user salt) via the
Workers-native `crypto.subtle` API. This is a deliberate choice over bcrypt/scrypt/argon2:
those are not implemented in the `workerd` runtime's WebCrypto surface, and pulling in a WASM
port for two user accounts is a dependency this app does not need
([Dependency Policy](../CODE_STANDARD.md#dependency-policy)).

### Tokens

- **Access token:** JWT, HS256, signed with `JWT_SIGNING_KEY` (a Workers secret, never a
  committed `vars` value — see [I1](../CODE_STANDARD.md#standard-i1)). Claims: `sub` (user id),
  `deviceId`, `iat`, `exp`. TTL 15 minutes.
- **Refresh token:** a random 256-bit value, stored in `UsersRoom` as its SHA-256 hash only
  (never the raw value), scoped to one `deviceId`, TTL 30 days. **Rotated on every use** — the
  old value is invalidated the moment a new one is issued, so a stolen-and-replayed refresh
  token is detectable (two devices presenting descendants of the same token is a reuse signal).
- The Worker verifies the access token on every request. `server/src/auth/jwt.ts` does
  verification only — no storage access, per [D1](../CODE_STANDARD.md#standard-d1).
- Device id is the same identifier already required for [F5.4](../CODE_STANDARD.md#standard-f5)
  LWW tie-breaks — introduced once, used for both auth and conflict resolution.

### Registration: admin-seeded, not open signup

> **Superseded by [ADR 0004](0004-open-registration.md).** Registration is now public, and
> the rate limiting this section's reasoning allowed us to skip is built. The rest of this
> ADR stands.

There is no public registration endpoint. With exactly two users, open signup is attack
surface with no product value. Accounts are created by `scripts/create-user.ts`, a script that
prints what it's about to do and prompts for confirmation before writing to `UsersRoom`, per
[A2](../CODE_STANDARD.md#standard-a2) — this is a script that touches production data.

### List sharing: signed invite link

- The list owner requests an invite for a list they own; the Worker mints a short-lived
  (7-day) JWT scoped to that one `listId`, distinct from an access token (different claim
  shape, cannot be used to authenticate as a user). This is the "invite code" shared out of
  band (a link, a QR code — the mechanism is a UI concern, not a protocol one).
- The invitee, already logged in with their own account, POSTs the invite token to an accept
  endpoint. The Worker verifies the token, then writes a membership row
  (`userId`, `listId`, `role`) to `UsersRoom`.
- **`ListRoom` never checks membership itself.** Every request is authorized by the Worker
  against `UsersRoom` membership before it is forwarded to the list's DO. The DO trusts the
  Worker; the Worker trusts nothing until it has checked `UsersRoom`. This keeps authorization
  logic in one place instead of duplicated per-DO.

## Consequences

- No third-party identity provider, no OAuth flow, no external dependency for something two
  known people need.
- `UsersRoom` is a second kind of Durable Object beyond `ListRoom`; it does not violate
  [D3](../CODE_STANDARD.md#standard-d3) — it is a singleton by construction (one fixed name),
  not one-per-entity, and still gets its own migrations under
  [G1](../CODE_STANDARD.md#standard-g1).
- Losing `JWT_SIGNING_KEY` invalidates every session at once (all access tokens fail
  verification); this is treated as an acceptable, rare, fully-recoverable event, not a
  reason to add key rotation infrastructure for two users.
- Refresh token rotation means a client that loses the rotation response (network drops after
  the server issued a new token but before the client saved it) is logged out and must
  re-authenticate. Given only two users and a password each can re-enter, this is judged
  simpler than adding a grace-period grace window.

## Implementation notes

Added when the decision was built, 2026-09-08. These do not change the decision; they record
what it turned out to require.

- **PBKDF2 cost is capped by the plan, not by taste.** Measured: 10,000 iterations ≈ 4.5 ms,
  600,000 ≈ 248 ms. The Workers free plan allows 10 ms of CPU per invocation, so the shipped
  value is 10,000. The household's compensating control is *generated* passwords — iteration
  count only protects guessable secrets. The cost is stored per user, so raising it later
  costs one constant and a plan upgrade, and existing accounts re-hash on next login. See
  [L1](../CODE_STANDARD.md#standard-l1).
- **The access token rides on the WebSocket upgrade request, not in the `hello` message.**
  The Context section above notes that a bearer token suits a native client "in the
  connection message anyway"; that turned out to be incompatible with the decision's own
  rule that the *Worker* authorizes before anything reaches a `ListRoom`, since the Worker
  cannot see post-upgrade messages. OkHttp sets headers on an upgrade, so the Android client
  is unaffected. A browser cannot, which is a problem `web/` will have to solve.
- **Ownership is claimed, not granted.** Entity ids are client-generated (F5.1), so the
  server never mints a list id and cannot assign an owner at creation. The first caller to
  claim an unheld id becomes its owner; re-claiming your own is a no-op (so an outbox retry
  is harmless), and claiming someone else's is refused.
- **Account creation goes through an `ADMIN_TOKEN`-guarded Worker route.** That is a second
  secret alongside `JWT_SIGNING_KEY`. It fails closed: an unset or empty `ADMIN_TOKEN` means
  nobody, never everybody.
- **Not built, and deliberately:** login rate limiting. Two users, no public registration,
  and generated passwords make an online guessing attack uninteresting; adding it would mean
  another DO or a KV namespace. Revisit if the user base ever grows past the two this ADR
  assumes.

## Alternatives considered

- **Firebase Auth / Supabase Auth** — rejected, see Context.
- **OAuth via Google/GitHub** — rejected: adds a third party and a consent screen for two
  people who already have a password relationship with each other, not with an IdP.
- **Session cookies instead of JWT** — rejected: the Android client is a native app talking
  over WebSocket and HTTP, not a browser; cookies add no value over a bearer token here and
  complicate the WebSocket handshake, which needs the token in the connection message anyway.
- **Open registration with email verification** — rejected as unnecessary infrastructure
  (transactional email sending, verification token storage) for a fixed set of two accounts
  that will not grow.
