-- Voice corrections (ADR 0016, PROTOCOL.md "Voice corrections"): what voice
-- wrote and what the person corrected it to, on purpose, from a row's
-- "Correct dictation". Personal to the account, so a reinstall keeps the
-- learning. heard_key is `heard` lowercased with spacing collapsed: one row per
-- mishearing per language, the latest correction winning. The newest 500 per
-- user are kept; erased with the account (ADR 0007).
CREATE TABLE IF NOT EXISTS voice_corrections (
  user_id TEXT NOT NULL,
  heard_key TEXT NOT NULL,
  language TEXT NOT NULL,
  heard TEXT NOT NULL,
  saved TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (user_id, heard_key, language)
);
CREATE INDEX IF NOT EXISTS voice_corrections_by_user ON voice_corrections (user_id, created_at);

INSERT INTO _migrations (version) VALUES (14);
