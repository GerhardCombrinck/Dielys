-- Whether the list is put away for everybody on it (ADR 0014, PROTOCOL.md
-- "Mutations"). List state rather than a membership choice, so it lives on the
-- list row and goes down the changelog like the title does.
--
-- 0 for every existing list: nothing was archived before the field existed.
-- An INTEGER because SQLite has no boolean; selectList turns it back into one.
ALTER TABLE lists ADD COLUMN archived INTEGER NOT NULL DEFAULT 0;

INSERT INTO _migrations (version) VALUES (4);
