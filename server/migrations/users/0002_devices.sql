-- FCM registration tokens, one row per device (CODE_STANDARD.md M2).
--
-- Its own table rather than a column on refresh_tokens, even though M2 talks
-- about the token living "alongside the refresh token row": refresh tokens
-- rotate on every use (L1), so each rotation writes a new row and would have to
-- carry the push token forward or lose it. A device outlives its sessions.
--
-- Keyed by device_id, which is what makes handing a phone to somebody else
-- work: the new owner's login upserts this row and it stops belonging to the
-- previous account, instead of the phone quietly being woken for two people.
CREATE TABLE IF NOT EXISTS devices (
  device_id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  -- The registration token FCM issued this install. Not a secret in the sense
  -- a password is, but it is the address of somebody's phone, so it is never
  -- logged (D4) and never sent anywhere but Google.
  fcm_token TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

-- The fan-out query is "every device belonging to a member of this list", so
-- it joins memberships to devices on user_id.
CREATE INDEX IF NOT EXISTS idx_devices_user ON devices (user_id);

INSERT INTO _migrations (version) VALUES (2);
