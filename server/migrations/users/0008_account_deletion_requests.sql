-- Web account deletion (docs/adr/0007-account-deletion.md, "From the web").
--
-- Someone without the app asks at dielys.com/account/delete; a link carrying a
-- token is mailed to the account's address, and the account goes only when
-- that link's page is confirmed. Proof of the inbox is the whole of the
-- authentication, the same as a magic link (ADR 0005).
--
-- Its own table rather than a `purpose` column on magic_links: a token minted
-- to delete an account must never be redeemable as a sign-in, and a separate
-- table makes that true by construction instead of by a WHERE clause every
-- magic-link query has to remember.
--
-- Keyed by token_hash (plain SHA-256 of 256 random bits, like refresh and
-- magic-link tokens), holding the user id rather than the email: the account
-- was resolved when the request was made, and there is no reason to keep a
-- second copy of the address here.
CREATE TABLE IF NOT EXISTS account_deletion_requests (
  token_hash TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  expires_at TEXT NOT NULL,
  created_at TEXT NOT NULL
);

-- A new request replaces the outstanding one for the same account, and
-- deleting the account removes any left over.
CREATE INDEX IF NOT EXISTS idx_account_deletion_user ON account_deletion_requests (user_id);

CREATE INDEX IF NOT EXISTS idx_account_deletion_expires ON account_deletion_requests (expires_at);

INSERT INTO _migrations (version) VALUES (8);
