-- Per-member notification choice for a list (PROTOCOL.md "Notifications for a
-- list", ADR 0012).
--
-- On memberships for the same reason `position` is: the two people on a shared
-- list each choose their own, and it is nothing the list's changelog should
-- broadcast.
--
-- A comma-separated set of NotifyEvent values rather than one column per kind,
-- so a later kind is a new value, not a new migration. Written only by
-- updateMembershipNotify from a validated, de-duplicated list; '' is "none",
-- which is every existing membership and every new one until somebody opts in.
ALTER TABLE memberships ADD COLUMN notify_events TEXT NOT NULL DEFAULT '';

INSERT INTO _migrations (version) VALUES (12);
