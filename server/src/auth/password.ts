/**
 * Password hashing and refresh-token hashing. PBKDF2-SHA256 via
 * `crypto.subtle` (L1) — bcrypt/scrypt/argon2 are not in workerd's WebCrypto
 * surface, and a WASM port for two accounts is a dependency this app does not
 * need (docs/adr/0002-authentication.md).
 *
 * No storage access here (D1).
 */
import { decodeBase64Url, encodeBase64Url } from "../lib/base64url.js";

/**
 * Deliberately far below the OWASP guidance of ~600,000, and this is a
 * measured constraint rather than an oversight:
 *
 *   10,000 iterations ≈ 4.5 ms      600,000 ≈ 248 ms
 *
 * The Workers **free** plan allows 10 ms of CPU per invocation, so anything
 * above roughly 15,000 makes login fail outright with `exceededCpu`. Raising
 * this requires the Workers Paid plan (30 s of CPU), at which point it should
 * go to 600,000.
 *
 * The cost is stored per user (`users.password_iterations`), so raising this
 * constant does not invalidate existing accounts: an old hash keeps verifying
 * at the count it was created with, and is re-hashed at the current count on
 * the owner's next successful login.
 *
 * What makes this acceptable for now: two accounts, and the hash is only
 * reachable by someone who has already dumped the UsersRoom DO's storage.
 */
export const PASSWORD_ITERATIONS = 10_000;

const SALT_BYTES = 16;
const DERIVED_BITS = 256;

export interface PasswordHash {
  hash: string; // base64url
  salt: string; // base64url
  iterations: number;
}

async function derive(password: string, salt: Uint8Array, iterations: number): Promise<string> {
  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(password),
    "PBKDF2",
    false,
    ["deriveBits"],
  );
  const bits = await crypto.subtle.deriveBits(
    { name: "PBKDF2", hash: "SHA-256", salt, iterations },
    key,
    DERIVED_BITS,
  );
  return encodeBase64Url(new Uint8Array(bits));
}

export async function hashPassword(
  password: string,
  iterations: number = PASSWORD_ITERATIONS,
): Promise<PasswordHash> {
  const salt = crypto.getRandomValues(new Uint8Array(SALT_BYTES));
  return {
    hash: await derive(password, salt, iterations),
    salt: encodeBase64Url(salt),
    iterations,
  };
}

export async function verifyPassword(password: string, stored: PasswordHash): Promise<boolean> {
  let salt: Uint8Array;
  try {
    salt = decodeBase64Url(stored.salt);
  } catch {
    return false;
  }
  const candidate = await derive(password, salt, stored.iterations);
  return timingSafeEqual(candidate, stored.hash);
}

/**
 * A 256-bit random value (L1). Returned raw to the caller exactly once — only
 * its SHA-256 hash is ever stored, so a dump of `refresh_tokens` cannot be
 * replayed as a session.
 */
export function generateRefreshToken(): string {
  return encodeBase64Url(crypto.getRandomValues(new Uint8Array(32)));
}

export async function hashRefreshToken(token: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(token));
  return encodeBase64Url(new Uint8Array(digest));
}

/**
 * Comparison whose duration does not depend on where the first difference is.
 * `===` on a hash leaks, byte by byte, how much of a guess was right.
 */
export function timingSafeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i += 1) {
    diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  }
  return diff === 0;
}
