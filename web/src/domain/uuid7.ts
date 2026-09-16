/**
 * UUIDv7 (RFC 9562): a 48-bit millisecond timestamp, a version/variant nibble
 * pair, and the rest random. Entity and device ids are client-generated
 * (F5.1) — the server never mints one — so every create needs one of these.
 *
 * The timestamp prefix is what makes `id` order and creation order agree
 * closely enough to be a useful tiebreak (see position.ts's `(position, id)`
 * ordering); nothing here depends on it being exact.
 */
export function uuid7(now: number = Date.now()): string {
  const random = new Uint8Array(16);
  crypto.getRandomValues(random);
  const bytes = Array.from(random);

  const timestamp = Math.floor(now);
  bytes[0] = Math.floor(timestamp / 2 ** 40) & 0xff;
  bytes[1] = Math.floor(timestamp / 2 ** 32) & 0xff;
  bytes[2] = Math.floor(timestamp / 2 ** 24) & 0xff;
  bytes[3] = Math.floor(timestamp / 2 ** 16) & 0xff;
  bytes[4] = Math.floor(timestamp / 2 ** 8) & 0xff;
  bytes[5] = timestamp & 0xff;

  const byte6 = bytes[6] ?? 0;
  const byte8 = bytes[8] ?? 0;
  bytes[6] = 0x70 | (byte6 & 0x0f); // version 7
  bytes[8] = 0x80 | (byte8 & 0x3f); // variant 10xxxxxx

  const hex = bytes.map((b) => b.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}
