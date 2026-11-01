-- Voice corrections become one library shared by every account (ADR 0016,
-- PROTOCOL.md "Voice corrections"). Each distinct correction of a mishearing
-- is a row with how many times it was submitted; the one answered for a
-- mishearing is the most submitted, the latest on a tie. Rows carry no user
-- id: a contribution is anonymous and outlives the account that made it.
-- heard_key and saved_key are the texts lowercased with spacing collapsed.
CREATE TABLE IF NOT EXISTS shared_voice_corrections (
  heard_key TEXT NOT NULL,
  language TEXT NOT NULL,
  saved_key TEXT NOT NULL,
  heard TEXT NOT NULL,
  saved TEXT NOT NULL,
  submissions INTEGER NOT NULL,
  last_at INTEGER NOT NULL,
  PRIMARY KEY (heard_key, language, saved_key)
);
CREATE INDEX IF NOT EXISTS shared_voice_corrections_recent ON shared_voice_corrections (last_at);

-- What was filed per account until now joins the shared library.
INSERT INTO shared_voice_corrections (heard_key, language, saved_key, heard, saved, submissions, last_at)
SELECT heard_key, language, lower(trim(saved)), heard, saved, COUNT(*), MAX(created_at)
FROM voice_corrections
GROUP BY heard_key, language, lower(trim(saved))
ON CONFLICT DO NOTHING;

DROP TABLE IF EXISTS voice_corrections;

INSERT INTO _migrations (version) VALUES (15);
