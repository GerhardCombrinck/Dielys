# 0005 — Passwordless sign-in: email magic link via Brevo

**Status:** Accepted
**Date:** 2026-09-10
**Builds on:** [ADR 0002](0002-authentication.md) (token shapes, refresh rotation — unchanged)
and [ADR 0004](0004-open-registration.md) (open registration, rate limiting pattern reused
here). Revisits 0004's "Email verification — rejected again" and 0002's "Open registration
with email verification — rejected as unnecessary infrastructure": the household now wants
exactly that infrastructure, for a reason neither ADR anticipated.

## Context

A password is one more thing to generate, store in a password manager, and type on a new
device. The household [owns its infra](../../CLAUDE.md) — Firebase Auth and Supabase Auth stay
rejected for the reason 0002 gave — but a self-issued JWT does not require a password
specifically; it requires *some* proof of control over an identity. Control of an email inbox
is that proof, and Dielys already needs a mail sender for the next thing this ADR also enables:
sharing a list by typing someone's email instead of handing them a join link.

0002 and 0004 both declined transactional email as infrastructure not worth building for a
handful of accounts. What changed is that email-based sharing (a follow-up to this ADR) needs a
mail sender anyway, which removes the "declined infrastructure" objection at the root: the
sender is being built regardless, so gating it behind sign-in costs nothing further.

A one-time code (typed by hand) was the first design and was replaced by a tapped link before
this ADR shipped: nothing about the code has to be memorised or retyped if the email is opened
on the same phone that asked for it, which is the ordinary case for a household app.

## Decision

### `POST /auth/magic/request` and `POST /auth/magic/verify` replace the password prompt

- `request` takes `{ email }`, mints a 256-bit random token, mails a link carrying it via
  Brevo's transactional API, and stores only the token's hash. Answered identically whether or
  not the email has an account — unlike registration, there is nothing to leak, because
  `verify` creates the account on first use.
- `verify` takes `{ token, deviceId }` — no email. The token alone names the request that
  minted it; asking the caller to also supply the email would be a second value with no purpose
  but to agree with the first. A correct, unexpired token that matches no existing account
  creates one and signs in, in one call — the collapse `RegisterRequest` already uses, extended
  to a second on-ramp.
- **`POST /auth/register` and `POST /auth/login` are not removed by this ADR.** Existing
  password accounts keep working. This is additive infrastructure; retiring the password path
  is a separate, later decision once the Android client has switched its primary flow to the
  magic link and the household has confirmed nobody depends on a stored password.

### The token is a link, opened by the app itself via an Android App Link

The mailed link is `https://dielys.com/magic?token=…`. `dielys.com` is a domain the household
already holds on Cloudflare for this project. Android's App Links let a verified domain open
directly in the owning app instead of a browser — verification is a one-time fetch of
`/.well-known/assetlinks.json`, served by this Worker (`GET /.well-known/assetlinks.json`,
`index.ts`), naming the app's package (`za.co.dielys`) and its signing certificate's SHA-256
fingerprint(s) (`ANDROID_CERT_SHA256_FINGERPRINTS`, a Worker secret — not sensitive once
published in that document, but not yet known to this repo until the release keystore's
fingerprint is generated).

A custom URI scheme (`dielys://…`) was the alternative and is not used: several mail apps,
Gmail's among them, do not reliably let a user tap a custom-scheme link out of HTML mail, which
would make the whole feature flaky in exactly the case it exists for. `GET /magic` (`index.ts`)
still exists as a plain HTML fallback page for the case App Link verification has not completed
yet, or the link is opened somewhere without the app installed — it does nothing with the token
itself, since only the app's own `/auth/magic/verify` call can use one.

`APP_BASE_URL` (`https://dielys.com`, a `vars` value — public by nature, not a secret) is the
same in `dev` and `prod`: the App Link domain Android verifies against is one fixed thing
regardless of which backend deployment issued a given link, since both are sending someone to
the one installed app.

### The token is hashed exactly like a refresh token, not like a password

`server/src/auth/password.ts`'s existing `hashRefreshToken` (plain SHA-256 of a 256-bit random
value) is reused as-is for the magic-link token — no new hashing module. Unlike the abandoned
OTP code, this needs no PBKDF2-style work factor and no per-attempt guess limit: 256 bits is far
too large a space to brute-force regardless of the hash, the way a 6-digit code would not have
been. This sidesteps the ceiling `PASSWORD_ITERATIONS` lives under entirely — [0002's
Implementation
notes](0002-authentication.md#implementation-notes) record `PASSWORD_ITERATIONS` capped at
10,000 (≈4.5 ms) because the Workers free plan allows 10 ms of CPU per invocation; a magic link
has no equivalent cost to cap, on either plan.

### One outstanding link per email, single-use

`magic_links` (`server/migrations/users/0005_magic_links.sql`) is keyed by `token_hash`, so
`verify` needs only the token to look up the row — no email round trip. Requesting a new link
for an email deletes any row already outstanding for it (`deleteMagicLinksForEmail`), so an
older, forwarded, or accidentally-reused link stops working the moment a fresh one is
requested. `verify` deletes the row it found on every path through it — matched-and-valid,
matched-and-expired, alike — so a tapped link cannot be tapped twice regardless of outcome.

### Rate limits, same shape as ADR 0004's

| Bucket                     | Limit | Window     | Why                                                          |
| --------------------------- | ----- | ---------- | ------------------------------------------------------------- |
| `magic-request` per client  | 5     | 1 hour     | A person asking for a link a handful of times while they find their inbox. |
| `magic-request` per email   | 3     | 15 minutes | Stops mailbombing one address by spreading requests across source IPs. |
| `magic-verify` per client   | 20    | 15 minutes | Not a guessing defence (256 random bits) — cheap insurance against a client hammering the endpoint. |

The per-email bucket is keyed by `emailKey` — a keyed hash of the address under
`JWT_SIGNING_KEY`, the same D4 reasoning `clientKey` already applies to an IP — so
`rate_limits.bucket` never becomes a second plaintext copy of a user's email, even though the
address is already a plaintext lookup key in `users` for an unrelated reason.

### An OTP-created — now magic-link-created — account gets a random, undiscoverable password hash

`users.password_hash` / `password_salt` / `password_iterations` stay `NOT NULL` — this ADR does
not touch that schema. An account created through `verifyMagicLink` is given a password hash
derived from 256 random bits nobody retains, rather than a migration to make the column
nullable for a case that is, for now, additive alongside a password path that still works.
`/auth/login` against such an account fails correctly and permanently — the account has no
password, full stop, and the random hash is just how that fact is represented in a schema not
designed to say it directly. If the password path is later retired, that `NOT NULL` constraint
(and this workaround) is the first thing the follow-up ADR should remove.

### Brevo, not Cloudflare, sends the mail

Cloudflare has no consumer transactional-email product — Email Routing/Workers is inbound
routing and forwarding, not an API for sending arbitrary mail to arbitrary recipients with
deliverability guarantees. The household already holds a Brevo account. `server/src/email/brevo.ts`
is a thin `fetch` wrapper (`POST /v3/smtp/email`), on the same "no vendor SDK" reasoning
`push/fcm.ts` gives for skipping the Firebase Admin SDK: the whole of what Brevo needs is one
API key in a header and one JSON body.

`BREVO_API_KEY` and `EMAIL_FROM` are Workers secrets (`wrangler secret put`), never `vars`
(I1) — `EMAIL_FROM` is not sensitive the way an API key is, but it belongs to whoever runs a
given deployment, the same reasoning that puts `ADMIN_TOKEN` in a secret rather than committed
config.

### `/auth/magic/*` fails closed on missing configuration

The Worker checks `BREVO_API_KEY` and `EMAIL_FROM` before either route does anything else —
same shape as `isUsableSigningKey` gating the whole Worker on `JWT_SIGNING_KEY`. A deployment
that has not run `wrangler secret put` for these serves `503 internal` rather than reaching
`UsersRoom`, spending a rate-limit slot, and failing later at the Brevo call. Unlike
`FCM_SERVICE_ACCOUNT_JSON` — where "absent" is a normal, silently-degraded state because push
is a hint and sync still works without it — an unconfigured mail sender means the entire
passwordless sign-in path does not work, so it is treated as the failure it is rather than
logged quietly.

## Consequences

- **Sending mail is no longer best-effort.** Unlike an FCM wake push, a link that Brevo rejects
  or that never arrives is a login the caller cannot complete — `sendMagicLinkEmail` returning
  `false` fails the request instead of being swallowed the way a dead FCM token is.
- **A new failure mode: Brevo is down or misconfigured.** Answered with `internal`/503, not a
  silent no-op — the caller needs to know the link was not sent, unlike registration's
  enumeration tradeoff where silence would be the worse lie in a different way.
- **The household's Brevo account and the `dielys.com` domain are now production
  infrastructure**, not side tools. Brevo's sending reputation and API key, and the domain's
  `assetlinks.json`, matter to whether anyone can log in.
- **App Link verification is a manual, external step this ADR cannot complete on its own**: it
  needs the release keystore's SHA-256 fingerprint (not yet generated as of this ADR) set as
  `ANDROID_CERT_SHA256_FINGERPRINTS`, the `dielys.com` route pointed at the Worker
  (`wrangler.jsonc`, done), and the Android manifest's own intent filter (not done by this
  ADR — Android-side follow-up).
- **Two live auth paths increase surface area.** Rate limits, validation and error codes exist
  in parallel for password and magic-link until a follow-up ADR retires one. Judged acceptable
  short term: the alternative was a single PR that both adds passwordless sign-in and rips out
  the password path Android still depends on.
- **This unlocks share-by-email as a follow-up**, using the same `email/brevo.ts` sender to
  notify someone who does not yet have an account, and FCM (already built, `push/fcm.ts`) to
  notify one who does. Not built by this ADR.

## Alternatives considered

- **A typed one-time code** — the original design for this ADR, replaced before shipping: a
  tapped link needs no retyping when opened on the same phone that requested it, which is the
  ordinary case here, and removes the guess-limiting machinery (`attempts`, a max-tries ceiling)
  a short code would have needed.
- **A custom URI scheme (`dielys://…`) instead of an HTTPS App Link** — rejected: unreliable
  from HTML mail in several mail apps, Gmail's among them, for the exact use case this feature
  exists for.
- **Cloudflare Turnstile in front of `/auth/magic/request`** — not rejected, deferred: worth
  adding if the counters here turn out to be the thing actually attacked, same position ADR
  0004 took on Cloudflare Rate Limiting rules. Configuration outside the repo is invisible to
  `scripts/verify.sh`.
- **Cloudflare Access / Zero Trust** — rejected: built for gating internal tools behind a
  company identity provider, not for authenticating a consumer app's own end users.
- **A nullable `password_hash` column** — rejected for this ADR: a schema migration to properly
  represent "this account has no password" is the right move once the password path is actually
  retired, not before, while both paths must keep working.
