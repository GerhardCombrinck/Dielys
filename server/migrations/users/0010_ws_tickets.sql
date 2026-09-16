-- A short-lived, single-use credential a browser can put in the WebSocket
-- upgrade's query string, since unlike OkHttp it cannot set Authorization on
-- that request (docs/adr/0009-web-websocket-ticket-auth.md).
--
-- Same shape as refresh_tokens (256 random bits, only the hash stored, the
-- spent row kept rather than deleted) but its own table: a ws ticket lives
-- for a minute, not 30 days, and a second presentation is simply refused
-- rather than treated as a signal to revoke every session for the user — the
-- blast radius of a leaked one is a minute, not a household's sessions.
CREATE TABLE IF NOT EXISTS ws_tickets (
  ticket_hash TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  device_id TEXT NOT NULL,
  issued_at TEXT NOT NULL,
  expires_at TEXT NOT NULL,
  used_at TEXT
);

CREATE INDEX IF NOT EXISTS idx_ws_tickets_expiry ON ws_tickets (expires_at);

INSERT INTO _migrations (version) VALUES (10);
