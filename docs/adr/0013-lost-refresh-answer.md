# 0013 — A refresh whose answer never arrived is answered again, not treated as theft

**Status:** Accepted
**Date:** 2026-10-02
**Amends:** [ADR 0002](0002-authentication.md)'s consequence on a lost rotation response, and
[L1](../CODE_STANDARD.md#standard-l1)'s reuse rule. Everything else about refresh tokens —
256 random bits, stored as a hash, device-scoped, 30-day sliding TTL, rotated on every use —
stands.

## Context

A refresh token is single-use. Presenting one that was already exchanged is L1's compromise
signal: `UsersRoom` revokes every refresh token the user has, on every device.

That signal cannot tell theft from the commonest failure there is: the client asked, the server
rotated, and the answer never reached the client. On a phone this is routine, not exotic:

- Access tokens last 15 minutes, so every device refreshes several times a day.
- Android refreshes from the socket when a list is opened after a while away. If the person
  backgrounds or closes the app at that moment, the coroutine is cancelled, and `withContext`
  throws after the server has already answered. The new token is dropped.
- A connection that drops after the request is sent, or a browser tab closed mid-request, has
  the same result.

The client still holds the spent token. Its next refresh presents it, and the server signs the
whole account out — the phone *and* the web, both at once. People see this as the app
"randomly" signing them out.

ADR 0002 knew and accepted this: two users, each with a password they could re-type. Neither
holds any more. Registration is open (ADR 0004), sign-in is by emailed link or code (ADR 0005,
0008), and being signed out means a trip to the inbox on every device.

## Decision

**A spent token whose replacement has never been used is answered again.**

- When a token is exchanged, its row records the hash of the token it was exchanged for
  (`refresh_tokens.replaced_by`, users migration 0013).
- When a spent token is presented, by the device it was issued to, and the token it was
  exchanged for is unused and unexpired, the client evidently never received that answer. The
  server issues a fresh token, and retires the unreceived one — marked spent and pointed at the
  fresh one, not deleted.
- Otherwise — the replacement has been used, there is no record of one (a row spent before
  migration 0013), or a different device is presenting it — it is a replay, and L1's response
  is unchanged: every session for that user is revoked.

The retired token pointing at the fresh one means that if the first answer *did* arrive after
all (two requests racing on one device), whichever token the client kept still works. The
chain only becomes a replay signal once it has moved on: once a token's successor has been
used, everything behind it is dead.

Supabase's auth server (GoTrue) applies the same rule to the same problem: a spent token that
is the parent of the session's active token is honoured, on the reasoning that its client never
stored the result.

## Consequences

- A lost answer costs nothing: the next refresh succeeds and nobody is signed out.
- Theft is still caught whenever the chain diverges through use. If a thief refreshes with a
  stolen token first, the legitimate client's next refresh finds the thief's replacement used,
  and everything is revoked, as before. If the legitimate client refreshes first and uses the
  result, a thief presenting the old token finds it used, and everything is revoked, as before.
- What is weaker: a thief holding a token that has been exchanged but whose replacement has not
  been used yet is answered rather than caught, and the legitimate device is answered in turn
  on its next refresh. The two alternate rather than ending in a revocation, until the thief
  stops or the legitimate device's replacement is used. The thief must also know the device id
  the token was issued to. A refresh token never leaves the device except in the body of
  `/auth/refresh` over TLS, and is never logged; this window is judged a far smaller risk than
  signing real people out several times a week.
- `rotateRefreshToken` now hashes the new token before reading the row, so the read, the
  decision and the write run without yielding. Two presentations of one token can no longer
  both find it unspent.
- Clients still save a rotated token as soon as it arrives. Android now does so even if the
  coroutine that asked is cancelled mid-request, and signs out only on a 401 from
  `/auth/refresh`. A 429 or a 5xx no longer ends a session; the web client already behaved this
  way (#83).
