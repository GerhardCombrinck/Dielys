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

See docs/SYNC.md for why, not just what.
