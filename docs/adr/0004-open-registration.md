# 0004 — Open registration, and the rate limiting it forces

**Status:** Accepted
**Date:** 2026-09-09
**Supersedes:** the "Registration: admin-seeded, not open signup" decision in
[ADR 0002](0002-authentication.md). Everything else in 0002 — token shapes, PBKDF2, refresh
rotation, invite links — stands unchanged.

## Context

ADR 0002 assumed a fixed user base of two, and made accounts an admin operation:
`scripts/create-user.ts` against an `ADMIN_TOKEN`-guarded route. That was right for the
household and wrong for the first person outside it. A friend who wants to use Dielys
currently needs the owner to be at a terminal, holding a production secret, to type their
email — and then to hand them a password over a channel neither of them chose.

The alternative considered first was invite-gated registration: account creation permitted
only when the caller presents a valid invite token from an existing list owner. It keeps the
door shut, but it also means nobody can hold an account of their own without someone else's
list, which makes "try the app" impossible and couples identity to sharing. The household
chose open registration instead, accepting the exposure it brings, and this ADR records that
exposure honestly rather than quietly.

## Decision

### `POST /auth/register` is public

- Body: `{ email, password, deviceId }`. Same shape as login plus nothing.
- Success returns a `TokenPair` — the same one login returns. Registering signs you in; a
  separate login round trip after it would be two chances to fail for one intent.
- `MIN_PASSWORD_LENGTH` (12) **is** enforced here, as it already was on the admin route.
  Login stays length-agnostic, because rejecting a short *login* tells an attacker their
  guess was too short to be real.
- The `ADMIN_TOKEN` route stays. It is how the first account on a fresh deployment gets made
  before anyone can reach a phone, and how `scripts/create-user.ts` keeps working.

### Rate limiting is no longer optional

ADR 0002 listed login rate limiting under "Not built, and deliberately", and its stated
reason was *"two users, no public registration, and generated passwords"*. This ADR removes
the middle clause, so the conclusion goes with it.

A fixed-window counter table lives in `UsersRoom`. It is already a singleton Durable Object
with SQLite, so this adds no storage primitive, no KV namespace and no second DO — the
objection 0002 raised against building it in the first place.

| Bucket                | Limit | Window     | Why                                            |
| --------------------- | ----- | ---------- | ---------------------------------------------- |
| `register` per client | 3     | 1 hour     | A person makes one account, not three.         |
| `register` global     | 20    | 24 hours   | Ceiling on storage and PBKDF2 CPU per day.     |
| `login` per client    | 10    | 15 minutes | Far above a human retyping a password.         |

Over the limit is `429` with code `rate-limited`. The global register bucket is deliberately
low: this is a household app, and a day that needs more than twenty new accounts is a day
something is wrong.

### Clients are identified by a keyed hash of their IP, never the IP

The bucket key is `HMAC-SHA256(JWT_SIGNING_KEY, ip)`, truncated. A plain SHA-256 of an IPv4
address is not an anonymisation — the whole space is enumerable in seconds — and storing the
address itself would put user data in the DO for no reason [D4](../CODE_STANDARD.md#standard-d4).
The HMAC costs one operation next to PBKDF2's ten thousand.

Rows are pruned opportunistically on each check, so the table stays proportional to recent
traffic rather than to all traffic ever.

## Consequences

- **Registration is an account-enumeration oracle, and cannot not be.** `already-exists` is
  the honest answer to "make me this account", and the alternative — answering as though it
  worked — would mean issuing no session, which is a worse lie. Login remains
  non-enumerable ([L2](../CODE_STANDARD.md#standard-l2)); registration is now the softer
  target, bounded to three probes per hour per client. Closing it properly needs email
  verification, which needs transactional email, which is the infrastructure 0002 declined
  and this ADR still declines.
- **A shared NAT shares a bucket.** Two people registering from one house within an hour is
  within the limit; a school or an office is not. Judged acceptable for an app with this
  audience, and the limit is one constant.
- **`JWT_SIGNING_KEY` now has a second job.** Rotating it re-keys every rate-limit bucket,
  which resets the counters. That is a security-neutral outcome of an event that already
  logs everybody out.
- **Storage grows with strangers now, not just with the household.** The global daily cap is
  the control. There is no account deletion endpoint ([L2](../CODE_STANDARD.md#standard-l2)),
  so an abandoned account stays; twenty rows a day at worst is not a problem worth a
  reaper for.
- **A new account has no lists.** It is a working, empty app until somebody invites them or
  they make a list of their own — which is the point of separating identity from sharing.

## Alternatives considered

- **Invite-gated registration** — rejected by the household, see Context. It was the smaller
  change and the tighter door, and it made an account meaningless without someone else's
  list.
- **A shared signup code in `vars`** — rejected: it is a password with no owner, it lives in
  a committed file or in a second secret, and it gets pasted into a chat once and then is
  not a control any more.
- **Email verification** — rejected again, for the reason 0002 gave: it needs a mail sender,
  a verification token store, and a bounce story, to protect a user base measured in
  friends.
- **Cloudflare Rate Limiting rules / Turnstile** — rejected for now: both are configuration
  outside the repo, which makes the limit invisible to anyone reading the code and untested
  by `scripts/verify.sh`. Revisit if the counters here are ever actually the thing being
  attacked.
