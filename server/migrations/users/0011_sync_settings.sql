-- The account's background-sync preference (ADR 0010, PROTOCOL.md
-- "Background sync setting"): whether Android's periodic WorkManager
-- catch-up runs at all, and how often. Effect is mobile-only, but it lives
-- here rather than in local device storage so it is readable and settable
-- from any client, web included.
--
-- Columns on `users` rather than a side table: one row per user already, no
-- history to keep, same shape SetListPositionRequest's `position` column on
-- `memberships` takes for the same reason.
ALTER TABLE users ADD COLUMN sync_enabled INTEGER NOT NULL DEFAULT 1;
ALTER TABLE users ADD COLUMN sync_interval_minutes INTEGER NOT NULL DEFAULT 30;

INSERT INTO _migrations (version) VALUES (11);
