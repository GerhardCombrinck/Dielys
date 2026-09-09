# data/sync/AGENTS.md

The outbox drain, cursor, and apply-change logic. This package is where every H3 offline
scenario lives or breaks.

- The outbox write and its local Room write MUST be one transaction (F5.7) — never write the
  entity first and the outbox row after in a separate call.
- The cursor advances only after a change is committed locally (F5.8), never on receipt.
- Every mutation MUST carry an idempotency key generated once, at creation time, not
  regenerated on retry — the retry must send the *same* key or F5.2 buys nothing.
- WorkManager, not a coroutine tied to a `ViewModel` scope — the drain must survive process
  death (H3.4).
- A change received over WebSocket and a change received via `?since=` catch-up MUST go
  through the same apply-change function. Two code paths here is how a gap gets missed.

## The socket

`SyncSockets` supervises one `ListSocketSession` per list; `ListSockets` in `data/remote`
is the transport behind it, so the session logic is testable without a device (H1).

- The socket is a latency optimisation and nothing else. Every scenario in H3 MUST still
  pass with it never connecting at all — FCM wakes a backgrounded phone (M2) and the
  half-hourly worker is the floor (H3.12).
- No mutation ever goes out over the socket. The outbox drains over HTTP because it has to
  work with the app not running (H3.4), and a second write path is a second place for F5.2
  to be wrong.
- The access token goes on the upgrade request as `Authorization: Bearer`, never in a frame
  after it (L3): the Worker authorises against `UsersRoom` before anything reaches
  `ListRoom`, and it never sees a frame.
- The upgrade also carries `deviceId` on the query string. `ListRoom` attaches it before
  `hello` arrives, which is what lets `UsersRoom` leave a connected device out of the wake
  fan-out — M2 says a device with an open socket MUST NOT also get a push for the same
  change.
- A message this build cannot parse is ignored, never fatal and never applied (F2/F3).
- App-level `ping`/`pong` on a 30s interval with one interval of grace, matching the Durable
  Object's `setWebSocketAutoResponse`, which answers at the edge without waking it. A missed
  pong closes the socket; the reconnect starts from the cursor (H3.11).
- A `GAP` from `ChangeApplier` pulls `?since=cursor` over HTTP (F5.6). Nothing here pages.
- Reconnect backoff is exponential with jitter, reset only once a session has handshaked.
  A 403 or a rejected `hello` stops that list rather than retrying it.

See docs/SYNC.md for why, not just what.
