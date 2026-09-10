/**
 * Fixed-window rate limits for the auth routes (L2, ADR 0004).
 *
 * Policy only — the limits, and how a caller is turned into a bucket key. The
 * counting is in `UsersRoom`, which owns the table; nothing here touches
 * storage (D1).
 *
 * Registration is public as of ADR 0004, which removed the premise ADR 0002
 * used to skip rate limiting entirely.
 */
import { encodeBase64Url } from "../lib/base64url.js";

const MINUTE_MS = 60 * 1000;
const HOUR_MS = 60 * MINUTE_MS;
const DAY_MS = 24 * HOUR_MS;

export interface RateLimit {
  /** Prefix of the bucket key, and the thing being limited. */
  readonly action: string;
  readonly limit: number;
  readonly windowMs: number;
}

/** A person makes one account, not three. */
export const REGISTER_PER_CLIENT: RateLimit = {
  action: "register",
  limit: 3,
  windowMs: HOUR_MS,
};

/**
 * A ceiling on what strangers can cost this deployment in a day, in DO storage
 * and in PBKDF2 CPU. Deliberately low: a household app that needs more than
 * twenty new accounts in a day is a household app something is happening to.
 */
export const REGISTER_GLOBAL: RateLimit = {
  action: "register-all",
  limit: 20,
  windowMs: DAY_MS,
};

/**
 * Far above a person retyping a password, far below anything worth calling an
 * online guessing attack. Refresh is not limited — it is not guessable.
 */
export const LOGIN_PER_CLIENT: RateLimit = {
  action: "login",
  limit: 10,
  windowMs: 15 * MINUTE_MS,
};

/**
 * A person asking for a link a handful of times while they find their inbox
 * (ADR 0005). Below this, and above it a client is minting links, not
 * reading them.
 */
export const MAGIC_REQUEST_PER_CLIENT: RateLimit = {
  action: "magic-request",
  limit: 5,
  windowMs: HOUR_MS,
};

/**
 * The second half of the same control, keyed by the target address rather
 * than the caller: without this, spreading requests for one victim's inbox
 * across many source IPs would still get past MAGIC_REQUEST_PER_CLIENT.
 * Lower than the per-client limit on purpose — mailbombing one address is the
 * thing this exists to stop.
 */
export const MAGIC_REQUEST_PER_EMAIL: RateLimit = {
  action: "magic-request-email",
  limit: 3,
  windowMs: 15 * MINUTE_MS,
};

/**
 * Not a guessing defence — a magic-link token is 256 random bits, not a
 * 6-digit code — but cheap insurance against a client hammering the endpoint,
 * on the same shape as LOGIN_PER_CLIENT.
 */
export const MAGIC_VERIFY_PER_CLIENT: RateLimit = {
  action: "magic-verify",
  limit: 20,
  windowMs: 15 * MINUTE_MS,
};

/** How many characters of the HMAC end up in the key. 128 bits of it. */
const KEY_CHARS = 22;

/**
 * Turns a client address into an opaque bucket key.
 *
 * Keyed, not merely hashed: the entire IPv4 space is four billion values, so a
 * plain SHA-256 of an address is reversible with a laptop and an afternoon. The
 * HMAC under `JWT_SIGNING_KEY` is what makes the stored value say nothing about
 * who it came from (D4) — and it costs one operation next to PBKDF2's ten
 * thousand.
 *
 * A caller with no address — local dev, a test, a request that reached the
 * Worker without `CF-Connecting-IP` — shares one bucket. That is the safe
 * direction: unidentified callers are limited together rather than not at all.
 */
export async function clientKey(address: string | null, signingKey: string): Promise<string> {
  if (address === null || address.length === 0) return "unattributed";
  return keyedHash(address, signingKey);
}

/**
 * Turns an email into an opaque bucket key for MAGIC_REQUEST_PER_EMAIL, on
 * the same reasoning as `clientKey`: the address itself must not end up
 * stored in `rate_limits.bucket` (D4), even though it is already a plaintext
 * lookup key in `users` — this table has a different job and should not grow
 * a second copy of the same PII for no reason.
 */
export async function emailKey(email: string, signingKey: string): Promise<string> {
  return keyedHash(email, signingKey);
}

async function keyedHash(value: string, signingKey: string): Promise<string> {
  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(signingKey),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const mac = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(value));
  return encodeBase64Url(new Uint8Array(mac)).slice(0, KEY_CHARS);
}

/** `CF-Connecting-IP` is set by the edge and cannot be spoofed by the client. */
export function clientAddress(request: Request): string | null {
  return request.headers.get("CF-Connecting-IP");
}

export function bucketFor(limit: RateLimit, clientKey: string): string {
  return `${limit.action}:${clientKey}`;
}
