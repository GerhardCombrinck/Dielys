-- The latest seq each list has reached, so `/auth/memberships` can say which
-- lists have anything new (PROTOCOL.md "Which lists have changed").
--
-- Here rather than read from each ListRoom on demand: asking every room during
-- one memberships call would cost exactly the requests this exists to save. The
-- ListRoom already tells UsersRoom about every accepted change, to send the wake
-- push (M2), so recording the head rides on a call that was being made anyway.
--
-- A lower bound, not the truth: that call is fire-and-forget, so a head can lag
-- the room it describes. Clients treat a list with no row, or one that looks
-- current, accordingly — see SyncEngine.catchUpAll's daily sweep.
--
-- Empty for every list until its next write, and a list with no row is simply
-- caught up the old way.
CREATE TABLE IF NOT EXISTS list_heads (
  list_id TEXT PRIMARY KEY,
  max_seq INTEGER NOT NULL
);

INSERT INTO _migrations (version) VALUES (7);
