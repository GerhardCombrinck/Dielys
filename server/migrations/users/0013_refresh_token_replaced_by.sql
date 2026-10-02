-- Which token a spent refresh token was exchanged for (ADR 0013).
--
-- A spent token presented again is either a replay or a client that never
-- received the answer to its refresh — a dropped connection, an app closed or
-- backgrounded mid-request. The two are told apart by whether the replacement
-- was ever used: a client that never got it cannot have presented it. Without
-- this link the server could only guess, and L1's guess was "replay", which
-- signed every device out over a lost response.
--
-- Null on every row spent before this migration, and on every unspent row.
-- A spent row with no replacement on record is treated as a replay, exactly as
-- before, so nothing already in the table is reinterpreted.
ALTER TABLE refresh_tokens ADD COLUMN replaced_by TEXT;

INSERT INTO _migrations (version) VALUES (13);
