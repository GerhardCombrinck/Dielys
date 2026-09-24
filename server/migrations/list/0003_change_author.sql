-- Who made each change (PROTOCOL.md "Notifications for a list", ADR 0012):
-- the account the Worker authenticated, so a phone can tell another member's
-- edit from its own account's edit made on another device. `device_id` cannot
-- answer that on its own.
--
-- Nullable, and null for every change written before this migration. Nothing
-- is backfilled: a list's past is already on everybody's phone, and a reader
-- treats null as "unknown author", never as "somebody else".
ALTER TABLE changes ADD COLUMN user_id TEXT;

INSERT INTO _migrations (version) VALUES (3);
