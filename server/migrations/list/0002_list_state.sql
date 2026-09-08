-- Everything ListRoom needs to be an actual list owner rather than a stub.
--
-- Applied by src/storage/migrations.ts, which gates on _migrations.version and
-- runs each file at most once inside one transaction. That gate is why the
-- ALTER below does not need an IF NOT EXISTS it cannot have.

-- The list's own entity. ListRoom owns exactly one row here (its own list),
-- but the table is keyed by id so a changelog row and a state row always agree
-- on what they are talking about.
CREATE TABLE IF NOT EXISTS lists (
  id TEXT PRIMARY KEY,
  title TEXT NOT NULL,
  background_photo_url TEXT,
  deleted_at TEXT,
  updated_at TEXT NOT NULL
);

-- Per-field provenance: which (server timestamp, device) last wrote each
-- field. This is what makes F5.4 per-field rather than per-row. Without it a
-- rename and a tick from two offline devices would resolve as one row-level
-- write and one of the two edits would vanish.
--
-- Time in a Workers DO is frozen between I/O, so two mutations handled in the
-- same turn genuinely do get identical timestamps — the device_id tiebreak is
-- a live code path, not a theoretical one.
CREATE TABLE IF NOT EXISTS field_meta (
  entity_type TEXT NOT NULL,
  entity_id TEXT NOT NULL,
  field TEXT NOT NULL,
  server_timestamp TEXT NOT NULL,
  device_id TEXT NOT NULL,
  PRIMARY KEY (entity_type, entity_id, field)
);

-- Room-scoped key/value. Holds "list_id": idFromName() is one-way, so a DO
-- cannot recover the list id it was addressed by. It records the first one it
-- is told and rejects any request that disagrees, which turns a Worker routing
-- bug into an error instead of a silent cross-list write.
CREATE TABLE IF NOT EXISTS room_meta (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
);

-- The changelog is a union of task and list changes; a client must not have to
-- sniff entity_json to tell which it is holding.
ALTER TABLE changes ADD COLUMN entity_type TEXT NOT NULL DEFAULT 'task';

CREATE INDEX IF NOT EXISTS idx_tasks_list ON tasks (list_id);
CREATE INDEX IF NOT EXISTS idx_tasks_deleted ON tasks (deleted_at);

INSERT INTO _migrations (version) VALUES (2);
