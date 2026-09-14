/**
 * Minting and hashing the code mailed beside a magic link (ADR 0008).
 */
import { MAGIC_CODE_LENGTH } from "@dielys/protocol";
import { MAGIC_CODE_ALPHABET } from "../domain/magiccode.js";
import { encodeBase64Url } from "../lib/base64url.js";

/**
 * Eight characters, each from one random byte masked to five bits. The
 * alphabet is exactly 32 long, so the mask is unbiased — no modulo skew.
 */
export function generateMagicCode(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(MAGIC_CODE_LENGTH));
  let code = "";
  for (const byte of bytes) code += MAGIC_CODE_ALPHABET[byte & 31];
  return code;
}

/**
 * HMAC-SHA256 under `JWT_SIGNING_KEY`, not a plain digest: a 40-bit code is
 * searchable offline in minutes, so a dumped `magic_links` table must not be
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
