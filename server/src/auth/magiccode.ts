/**
 * Minting and hashing the code mailed beside a magic link (ADR 0008).
 */
import { MAGIC_CODE_LENGTH } from "@dielys/protocol";
import { encodeBase64Url } from "../lib/base64url.js";

const CODE_SPACE = 10 ** MAGIC_CODE_LENGTH;

/**
 * The largest multiple of [CODE_SPACE] a `Uint32` can hold. Drawing below it
 * and taking the remainder is unbiased; a draw at or above it is thrown away
 * and drawn again, which happens about once in six thousand.
 */
const UNBIASED_CEILING = Math.floor(2 ** 32 / CODE_SPACE) * CODE_SPACE;

/** Six random digits, leading zeros kept. */
export function generateMagicCode(): string {
  const draw = new Uint32Array(1);
  for (;;) {
    crypto.getRandomValues(draw);
    const value = draw[0] as number;
    if (value < UNBIASED_CEILING) {
      return (value % CODE_SPACE).toString().padStart(MAGIC_CODE_LENGTH, "0");
    }
  }
}

/**
 * HMAC-SHA256 under `JWT_SIGNING_KEY`, not a plain digest: a 6-digit code is
 * searchable offline in milliseconds, so a dumped `magic_links` table must not be
 * enough to find one. The token hash is mixed in so the same code minted for
 * two links never stores the same value.
 */
export async function hashMagicCode(
  normalizedCode: string,
  tokenHash: string,
  signingKey: string,
): Promise<string> {
  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(signingKey),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const mac = await crypto.subtle.sign(
    "HMAC",
    key,
    new TextEncoder().encode(`magic-code:${tokenHash}:${normalizedCode}`),
  );
  return encodeBase64Url(new Uint8Array(mac));
}
