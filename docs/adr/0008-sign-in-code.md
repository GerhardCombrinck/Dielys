# 0008 — A typed code beside the magic link, and a review account

**Status:** Accepted
**Date:** 2026-09-13
**Amends:** [ADR 0005](0005-passwordless-email-magic-link.md), which replaced a typed one-time
code with the tapped link. The link stays the primary way in; this adds a code alongside it.

## Context

Two things the link alone cannot do:

1. **The email is read somewhere else.** Somebody asks for a link on their phone and opens
   the email on a laptop. Tapping the link there opens a browser page that can only say "open
   this on your phone". Today the only way forward is to forward the email to themselves.
2. **Google Play's reviewers cannot receive email.** The Play Console's *App access*
   declaration asks for credentials that get a reviewer past sign-in. A reviewer cannot read an
   inbox, so a magic link alone means the app cannot be reviewed.

ADR 0005 dropped the typed code because a 6-digit code is guessable in a way a 256-bit token
is not. That objection is about the code's size and how many guesses it gets, not about typing
a code at all.

## Decision

### Every magic-link email also carries an 8-character code

- Eight characters from Crockford's base32 alphabet (`0-9`, `A-Z` without `I L O U`), shown as
  `XXXX-XXXX`: 40 bits. Typing is forgiving: case, spaces and dashes are ignored, and `O`, `I`,
  `L` read as `0`, `1`, `1`, so a misread character is not a wrong one.
- Minted with the link, in the same `magic_links` row. Stored as an **HMAC under
  `JWT_SIGNING_KEY`**, not a plain hash: 40 bits would fall to an offline search of a dumped
  table in minutes, and the key is what makes a dump useless for that.
- `POST /auth/magic/verify-code` takes `{ email, code, deviceId }`. The email is needed here,
  unlike with the link, because a code is short enough to collide across addresses; it finds
  the outstanding row for that address.
- **Five wrong codes burn the link.** Asking for a new link is limited to three per address
  every 15 minutes (`MAGIC_REQUEST_PER_EMAIL`), so an attacker gets at most 15 guesses per 15
  minutes at a 40-bit space — about one chance in 70 billion. The per-client verify limit
  applies on top.
- A right code does exactly what a tapped link does: burns the row, creates the account on
  first use, and signs in. The link in the same email stops working, and vice versa — one row,
  one use.
- Wrong, spent, and unknown all answer `invalid-token`, as the link does; past its time is
  `token-expired`.

### A review account signs in with a fixed code

- Two Worker secrets, `REVIEW_EMAIL` and `REVIEW_CODE`, set only on the deployment Google
  reviews (prod). Absent, empty, or with a code shorter than 12 characters, the review account
  does not exist.
- `verify-code` with that email and that code signs in as that account (creating it the first
  time), with no `magic_links` row. Any other code for that address falls through to the
  ordinary check, so the rate limit and attempt counting still apply.
- Asking for a link for `REVIEW_EMAIL` answers as usual but **sends nothing**: the address is
  not a real inbox, and mail to it would bounce and cost the sending domain reputation.
- The review account is an ordinary account in every other way. It can be deleted like any
  other; it is recreated on the next sign-in with the code.

## Consequences

- The "check your email" screen gets a code field. Signing in on a laptop-read email is now
  "type eight characters" instead of "forward the email".
- A leaked `REVIEW_CODE` gives access to the review account and nothing else — no other address
  accepts it. Rotating it is a `wrangler secret put` and an update to the Play Console note.
- The code widens what a stolen inbox preview can do only as far as the link already did: both
  are in the same email and both expire after 15 minutes.

## Alternatives

- **A hidden password sign-in for the reviewer.** The server still has `/auth/login`, but the
  app would need a password entry nobody else is meant to find, and it helps no real user.
- **A review-only bypass on `/auth/magic/request`** that signs straight in. Puts a special case
  on the path everybody uses, and gives nothing to the person reading email on a laptop.
- **A 6-digit numeric code.** Easier to type, but 20 bits: with the same limits, a determined
  attacker's odds are a million times better. Eight base32 characters are still short enough
  to read off one screen and type on another.
