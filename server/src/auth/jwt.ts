/**
 * Token signing and verification. No storage access here (D1) — this module
 * proves a JWT is validly signed and unexpired; the caller decides what to do
 * with the claims. See docs/adr/0002-authentication.md and CODE_STANDARD.md L1.
 *
 * HS256 only. `alg` from the incoming header is never used to select an
 * algorithm — it is checked against the one algorithm this server signs with
 * and the token is rejected otherwise. Trusting an attacker-supplied `alg` is
 * the classic JWT forgery ("alg: none", or HS256-verified-against-an-RSA-key).
 */
import {
  decodeBase64Url,
  decodeBase64UrlUtf8,
  encodeBase64Url,
  encodeUtf8Base64Url,
} from "../lib/base64url.js";

/** 15 minutes (L1). Short, because a leaked access token cannot be revoked. */
export const ACCESS_TOKEN_TTL_SECONDS = 15 * 60;

/** 7 days (L3). Long enough to share out of band, short enough to expire unused. */
export const INVITE_TOKEN_TTL_SECONDS = 7 * 24 * 60 * 60;

export interface AccessTokenClaims {
  typ: "access";
  sub: string; // user id
  deviceId: string;
  iat: number;
  exp: number;
}

/**
 * Deliberately a different claim shape from an access token, and tagged with
 * its own `typ`, so an invite can never be replayed as a session (L3).
 *
 * `email` scopes the invite to one recipient: `addMembership` checks it
 * against the accepting account's own email and refuses a mismatch, so
 * holding the token is no longer sufficient to join — only the address it
 * was minted for can finish accepting it.
 */
export interface InviteTokenClaims {
  typ: "invite";
  listId: string;
  sub: string; // the user who issued the invite
  email: string; // normalized — the only account that may accept this invite
  iat: number;
  exp: number;
}

export type TokenClaims = AccessTokenClaims | InviteTokenClaims;

export type VerifyResult<T> =
  | { ok: true; claims: T }
  | { ok: false; reason: "malformed" | "bad-signature" | "expired" | "wrong-type" };

async function hmacKey(signingKey: string): Promise<CryptoKey> {
  return crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(signingKey),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign", "verify"],
  );
}

async function sign(claims: TokenClaims, signingKey: string): Promise<string> {
  const header = encodeUtf8Base64Url(JSON.stringify({ alg: "HS256", typ: "JWT" }));
  const payload = encodeUtf8Base64Url(JSON.stringify(claims));
  const body = `${header}.${payload}`;
  const signature = await crypto.subtle.sign(
    "HMAC",
    await hmacKey(signingKey),
    new TextEncoder().encode(body),
  );
  return `${body}.${encodeBase64Url(new Uint8Array(signature))}`;
}

/**
 * Verifies signature and expiry, then hands back untyped claims. Callers use
 * the typed wrappers below; this exists so both share exactly one signature
 * check rather than two that could drift.
 */
async function verify(
  token: string,
  signingKey: string,
  now: number,
): Promise<VerifyResult<Record<string, unknown>>> {
  const parts = token.split(".");
  if (parts.length !== 3) return { ok: false, reason: "malformed" };
  const [header, payload, signature] = parts as [string, string, string];

  let parsedHeader: unknown;
  try {
    parsedHeader = JSON.parse(decodeBase64UrlUtf8(header));
  } catch {
    return { ok: false, reason: "malformed" };
  }
  if (
    typeof parsedHeader !== "object" ||
    parsedHeader === null ||
    (parsedHeader as Record<string, unknown>).alg !== "HS256"
  ) {
    // Never dispatch on the token's own `alg`; only accept the one we sign with.
    return { ok: false, reason: "bad-signature" };
  }

  let signatureBytes: Uint8Array;
  try {
    signatureBytes = decodeBase64Url(signature);
  } catch {
    return { ok: false, reason: "malformed" };
  }

  // crypto.subtle.verify is constant-time; never compare signatures with ===.
  const valid = await crypto.subtle.verify(
    "HMAC",
    await hmacKey(signingKey),
    signatureBytes,
    new TextEncoder().encode(`${header}.${payload}`),
  );
  if (!valid) return { ok: false, reason: "bad-signature" };

  let claims: unknown;
  try {
    claims = JSON.parse(decodeBase64UrlUtf8(payload));
  } catch {
    return { ok: false, reason: "malformed" };
  }
  if (typeof claims !== "object" || claims === null || Array.isArray(claims)) {
    return { ok: false, reason: "malformed" };
  }

  const record = claims as Record<string, unknown>;
  const exp = record.exp;
  if (typeof exp !== "number") return { ok: false, reason: "malformed" };
  if (exp <= Math.floor(now / 1000)) return { ok: false, reason: "expired" };

  return { ok: true, claims: record };
}

export async function signAccessToken(
  claims: Pick<AccessTokenClaims, "sub" | "deviceId">,
  signingKey: string,
  now: number = Date.now(),
): Promise<string> {
  const issued = Math.floor(now / 1000);
  return sign(
    {
      typ: "access",
      sub: claims.sub,
      deviceId: claims.deviceId,
      iat: issued,
      exp: issued + ACCESS_TOKEN_TTL_SECONDS,
    },
    signingKey,
  );
}

export async function verifyAccessToken(
  token: string,
  signingKey: string,
  now: number = Date.now(),
): Promise<VerifyResult<AccessTokenClaims>> {
  const result = await verify(token, signingKey, now);
  if (!result.ok) return result;

  const { typ, sub, deviceId, iat, exp } = result.claims;
  // An invite token is validly signed by the same key — only `typ` stops it
  // being presented as a session (L3).
  if (typ !== "access") return { ok: false, reason: "wrong-type" };
  if (typeof sub !== "string" || typeof deviceId !== "string") {
    return { ok: false, reason: "malformed" };
  }
  if (typeof iat !== "number" || typeof exp !== "number") {
    return { ok: false, reason: "malformed" };
  }
  return { ok: true, claims: { typ: "access", sub, deviceId, iat, exp } };
}

export async function signInviteToken(
  claims: Pick<InviteTokenClaims, "listId" | "sub" | "email">,
  signingKey: string,
  now: number = Date.now(),
): Promise<string> {
  const issued = Math.floor(now / 1000);
  return sign(
    {
      typ: "invite",
      listId: claims.listId,
      sub: claims.sub,
      email: claims.email,
      iat: issued,
      exp: issued + INVITE_TOKEN_TTL_SECONDS,
    },
    signingKey,
  );
}

export async function verifyInviteToken(
  token: string,
  signingKey: string,
  now: number = Date.now(),
): Promise<VerifyResult<InviteTokenClaims>> {
  const result = await verify(token, signingKey, now);
  if (!result.ok) return result;

  const { typ, listId, sub, email, iat, exp } = result.claims;
  if (typ !== "invite") return { ok: false, reason: "wrong-type" };
  if (typeof listId !== "string" || typeof sub !== "string" || typeof email !== "string") {
    return { ok: false, reason: "malformed" };
  }
  if (typeof iat !== "number" || typeof exp !== "number") {
    return { ok: false, reason: "malformed" };
  }
  return { ok: true, claims: { typ: "invite", listId, sub, email, iat, exp } };
}

/** Reads a bearer token out of an Authorization header, or null. */
export function bearerToken(request: Request): string | null {
  const header = request.headers.get("Authorization");
  if (header === null) return null;
  const match = /^Bearer (.+)$/.exec(header);
  return match === null ? null : (match[1] as string);
}

/**
 * A signing key must be present and long enough to be worth signing with.
 *
 * Without this check an unset `JWT_SIGNING_KEY` is not an error: `env.X` is
 * `undefined`, `TextEncoder().encode(undefined)` encodes the literal string
 * "undefined", and the Worker goes on signing and verifying tokens with a key
 * anyone can guess. Failing closed is the only safe reading of a missing
 * secret — see I1, and the same reasoning as `authorizeAdmin`.
 *
 * 32 characters is the floor because the key is an HMAC-SHA256 secret; a short
 * one is brute-forceable offline from a single captured token.
 */
export const MIN_SIGNING_KEY_LENGTH = 32;

export function isUsableSigningKey(key: unknown): key is string {
  return typeof key === "string" && key.length >= MIN_SIGNING_KEY_LENGTH;
}
