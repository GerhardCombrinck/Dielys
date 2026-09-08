# 0001 — Backend on Cloudflare Workers + Durable Objects

**Status:** Accepted
**Date:** 2026-09-08

## Context

Wonderlys needs a backend for two users sharing to-do lists, with a hard requirement on
bulletproof sync: two phones editing the same list, one losing signal mid-shop, must never
double-cart an item or silently drop a change. The backend must also cost effectively nothing
— this is a household app, not a funded product.

Candidates evaluated, in the order they were seriously considered:

1. **AWS EC2 / Fargate / RDS in `af-south-1`** — a conventional server + Postgres. Works, well
   understood, in-region (Cape Town). Cost: ~USD 23–47/mo for two users, running 24/7 for a
   workload that is idle 99% of the time. See the AWS price evidence catalog for the af-south-1
   figures this estimate is based on.
2. **AWS serverless (Lambda + DynamoDB + Cognito)** — scales to zero, ~USD 1–2/mo at this
   traffic. Rejected on two grounds: cold starts on a household app used in bursts (arriving at
   the shop, opening the app) are exactly the latency pattern that hurts most, and DynamoDB
   throws away SQL fluency for no benefit at this scale — the data model is relational
   (lists, tasks, ordering, membership) and fights a key-value store.
3. **AWS Amplify DataStore** — offline-first sync framework, which is exactly the shape of
   problem this app has. Rejected: Gen 1 reaches end-of-life 1 May 2027, and Gen 2 has no
   offline replacement. Building the hardest part of this app (sync) on a framework with a
   published expiry date is not acceptable.
4. **Firebase / Supabase** — both solve realtime sync out of the box. Rejected on a standing
   preference: the household wants to own its data flow and triggers, not hand the sync engine
   to a platform that can change pricing or behaviour unilaterally.
5. **Oracle Cloud Always Free (Johannesburg)** — genuinely free, genuinely in-country. Rejected
   on operational risk: Always Free accounts have a documented history of being reclaimed for
   low utilisation, and capacity in the Johannesburg region is not guaranteed at signup. A
   household relies on this being up; a free tier that can vanish without notice is not a
   foundation.
6. **Cloudflare Workers + Durable Objects, free plan** — the chosen option.

## Decision

Backend runs on Cloudflare Workers with Durable Objects, TypeScript, free plan. One Durable
Object per list (`idFromName(listId)`), each with its own embedded SQLite storage. The DO is
the single-threaded owner of one list's state and the source of a per-list monotonic sequence
number — the property the rest of the sync design (F5 in the coding standard) is built on.
Realtime delivery uses the WebSocket Hibernation API so idle connections do not bill compute.

## Consequences

- Cost is USD 0/mo for compute and storage at this scale, plus the existing
  `inconsolutions.co.za` Cloudflare zone already used for DNS/TLS.
- Single-threading per DO removes an entire category of concurrency bug (no distributed locks,
  no read-modify-write races on one list) that would otherwise need to be solved by hand.
- **Durable Objects do not spawn in Africa.** The `afr` location hint is accepted by the
  platform but falls back to a nearby supported region — in practice, Europe. This means:
  - Round-trip time to the DO is ~170 ms from South Africa, not the ~10–20 ms a local server
    would give.
  - The list data at rest is physically located in the EU, not South Africa.
  - This is judged acceptable **only** because the architecture is local-first: every user
    action applies to local SQLite first and is never blocked on the network (see
    `docs/SYNC.md`), so the 170 ms is hidden from the user for their own edits and only visible
    as sync latency to the *other* device.
  - This finding would be disqualifying if this system ever became an Incon product handling
    South African user data under POPIA, which has data-residency implications this project
    does not need to satisfy for a two-person household list. If that ever changes, this
    decision must be revisited with a new ADR before any such use.
- No relational database licence or ops burden — DO SQLite is embedded, per-list, and migrates
  lazily (see [G1](../CODE_STANDARD.md#standard-g1)).
- Vendor lock-in to Cloudflare's DO API is real and accepted; the protocol layer
  (`protocol/`) is kept free of any Cloudflare-specific type so a future migration, if ever
  needed, is a server rewrite behind a stable contract, not a full-stack rewrite.

## Alternatives considered

See the numbered list under Context. All five alternatives remain rejected as of this writing;
none should be re-opened without a new ADR that states what changed.
