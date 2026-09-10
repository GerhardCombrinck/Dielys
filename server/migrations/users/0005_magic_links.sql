-- Magic-link sign-in tokens (docs/adr/0005-passwordless-email-magic-link.md).
--
-- Keyed by token_hash, not by email: the verify request carries only the
-- token from the link the user tapped, and a lookup by its hash is exactly
-- what that request can supply — needing the email too would be a second
-- value the caller has no reason to type in a passwordless flow.
--
-- token_hash is a plain SHA-256 digest (server/src/auth/password.ts
-- hashRefreshToken, reused as-is) of a 256-bit random value, the same
-- construction as refresh_tokens.token_hash — a dump of this table cannot be
-- replayed as a link, and unlike a short OTP code, the value is far too large
-- to brute-force regardless of the hash.
CREATE TABLE IF NOT EXISTS magic_links (
  token_hash TEXT PRIMARY KEY,
  email TEXT NOT NULL,
  expires_at TEXT NOT NULL,
  created_at TEXT NOT NULL
);

-- Requesting a new link for an email deletes the old row first (single
-- outstanding link per address, same "replaces rather than queues" reasoning
-- 0002 used for refresh tokens) — this index is that lookup.
CREATE INDEX IF NOT EXISTS idx_magic_links_email ON magic_links (email);

CREATE INDEX IF NOT EXISTS idx_magic_links_expires ON magic_links (expires_at);

INSERT INTO _migrations (version) VALUES (5);
