/**
 * base64url (RFC 4648 §5) — the encoding JWT uses, and the one this app uses
 * for every stored binary value. No padding, `-`/`_` instead of `+`/`/`.
 *
 * Hand-written rather than pulled in: it is fifteen lines, and `protocol/` and
 * `server/` both benefit from not taking a dependency for it (N1).
 */

export function encodeBase64Url(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export function decodeBase64Url(value: string): Uint8Array {
  const padded = value.replace(/-/g, "+").replace(/_/g, "/");
  const binary = atob(padded.padEnd(Math.ceil(padded.length / 4) * 4, "="));
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

export function encodeUtf8Base64Url(value: string): string {
  return encodeBase64Url(new TextEncoder().encode(value));
}

export function decodeBase64UrlUtf8(value: string): string {
  return new TextDecoder().decode(decodeBase64Url(value));
}
