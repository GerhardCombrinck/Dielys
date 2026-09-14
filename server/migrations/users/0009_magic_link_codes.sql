-- A typed code beside each magic link (docs/adr/0008-sign-in-code.md).
--
-- `code_hash` is an HMAC under JWT_SIGNING_KEY of the 8-character code mailed
-- with the link, never the code: 40 bits would fall to an offline search of a
-- dumped table, and the key is what stops that. Null on a row minted before
-- this migration, which simply cannot be redeemed by code.
--
-- `code_attempts` counts wrong codes against this row. The row is deleted at
-- the fifth, which burns the link in the same email too — one row, one use.
ALTER TABLE magic_links ADD COLUMN code_hash TEXT;
ALTER TABLE magic_links ADD COLUMN code_attempts INTEGER NOT NULL DEFAULT 0;

INSERT INTO _migrations (version) VALUES (9);
