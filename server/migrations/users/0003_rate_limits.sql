-- Fixed-window rate limiting for the auth routes (L2, ADR 0004).
--
-- Registration became public, so the reasoning ADR 0002 used to skip rate
-- limiting ("two users, no public registration") no longer holds. This table is
-- the whole mechanism: UsersRoom is already a singleton DO with SQLite, so the
-- objection 0002 raised — that limiting would mean another DO or a KV namespace
-- — does not apply to putting it here.
--
-- `bucket` is "<action>:<keyed hash of the client address>", or "<action>:all"
-- for a global ceiling. The address itself is never stored: a plain digest of an
-- IPv4 address is not an anonymisation, so the key is an HMAC under
-- JWT_SIGNING_KEY (D4).
--
-- Rows are pruned opportunistically on each check, so this stays proportional to
-- recent traffic rather than to all traffic ever.
CREATE TABLE IF NOT EXISTS rate_limits (
  bucket TEXT PRIMARY KEY,
  count INTEGER NOT NULL,
  -- Epoch milliseconds, not ISO: this is compared and arithmetic'd on every
  -- auth request and never read by a person.
  window_started_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_rate_limits_window ON rate_limits (window_started_at);

INSERT INTO _migrations (version) VALUES (3);
