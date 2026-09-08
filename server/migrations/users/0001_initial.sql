-- UsersRoom initial schema. One singleton DO, idFromName("users-v1"), holding
-- accounts, device-scoped refresh tokens and list membership.
-- See docs/adr/0002-authentication.md and CODE_STANDARD.md L1-L3.

CREATE TABLE IF NOT EXISTS _migrations (
  version INTEGER PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS users (
  id TEXT PRIMARY KEY,
  email TEXT NOT NULL UNIQUE,
  -- PBKDF2-SHA256, base64url. Salt is per-user and random; no plaintext and
  -- nothing reversible is ever stored (L1).
  password_hash TEXT NOT NULL,
  password_salt TEXT NOT NULL,
  -- Stored per user rather than as one global constant so the work factor can
  -- be raised later without invalidating existing accounts: an old hash keeps
  -- verifying at the count it was made with, and gets re-hashed at the current
  -- count on the owner's next successful login.
  password_iterations INTEGER NOT NULL,
  created_at TEXT NOT NULL
);

-- Refresh tokens are stored only as SHA-256 hashes of the issued value (L1) —
-- a dump of this table cannot be replayed as a session.
--
-- Rotated tokens are kept, with used_at set, rather than deleted: presenting a
-- token that has already been rotated is the reuse signal L1 requires, and a
-- deleted row is indistinguishable from a token that never existed.
CREATE TABLE IF NOT EXISTS refresh_tokens (
  token_hash TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  device_id TEXT NOT NULL,
  issued_at TEXT NOT NULL,
  expires_at TEXT NOT NULL,
  used_at TEXT
);

CREATE INDEX IF NOT EXISTS idx_refresh_user ON refresh_tokens (user_id);

CREATE TABLE IF NOT EXISTS memberships (
  user_id TEXT NOT NULL,
  list_id TEXT NOT NULL,
  role TEXT NOT NULL,
  created_at TEXT NOT NULL,
  PRIMARY KEY (user_id, list_id)
);

CREATE INDEX IF NOT EXISTS idx_memberships_list ON memberships (list_id);

INSERT INTO _migrations (version) VALUES (1);
