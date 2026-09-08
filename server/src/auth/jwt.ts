/**
 * Access-token verification only. No storage access here (D1) — this module
 * proves a JWT is validly signed and unexpired; the caller decides what to do
 * with the claims. See docs/adr/0002-authentication.md and CODE_STANDARD.md L1.
 */

export interface AccessTokenClaims {
  sub: string; // user id
  deviceId: string;
  iat: number;
  exp: number;
}

export async function verifyAccessToken(
  _token: string,
  _signingKey: string,
): Promise<AccessTokenClaims> {
  // TODO: HS256 verify via crypto.subtle, decode claims, check exp.
  throw new Error("not implemented");
}

export async function signAccessToken(
  _claims: Omit<AccessTokenClaims, "iat" | "exp">,
  _signingKey: string,
): Promise<string> {
  // TODO: HS256 sign, 15 minute TTL.
  throw new Error("not implemented");
}
