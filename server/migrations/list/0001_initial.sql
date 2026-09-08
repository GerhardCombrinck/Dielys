-- ListRoom initial schema. Applied lazily on first touch per DO (G1).

CREATE TABLE IF NOT EXISTS _migrations (
  version INTEGER PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS tasks (
  id TEXT PRIMARY KEY,
  list_id TEXT NOT NULL,
  title TEXT NOT NULL,
  done INTEGER NOT NULL DEFAULT 0,
  starred INTEGER NOT NULL DEFAULT 0,
  position TEXT NOT NULL,
  deleted_at TEXT,
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS changes (
  seq INTEGER PRIMARY KEY,
  idempotency_key TEXT NOT NULL UNIQUE,
  device_id TEXT NOT NULL,
  server_timestamp TEXT NOT NULL,
  entity_json TEXT NOT NULL
);

INSERT INTO _migrations (version) VALUES (1);
