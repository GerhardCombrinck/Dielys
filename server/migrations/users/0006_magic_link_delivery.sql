-- Delivery-status polling for a requested magic link. `request_id` is an
-- opaque handle handed to the client in RequestMagicLinkResponse — never the
-- email itself — so a poll can only ever ask about a request the caller
-- already made. `message_id` is Brevo's own id for the send, looked up
-- against its event-report API on each poll (UsersRoom.magicLinkStatus).
--
-- Both are nullable: a row from before this migration has neither, and a
-- send Brevo accepted without handing back a messageId still has a request
-- id but no way to ever report "delivered" (brevo.ts's SendResult).
ALTER TABLE magic_links ADD COLUMN request_id TEXT;
ALTER TABLE magic_links ADD COLUMN message_id TEXT;

CREATE INDEX IF NOT EXISTS idx_magic_links_request ON magic_links (request_id);

INSERT INTO _migrations (version) VALUES (6);
