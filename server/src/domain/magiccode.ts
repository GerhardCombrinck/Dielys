/**
 * The mailed sign-in code's alphabet and how typed input is read (ADR 0008).
 * Pure: generating one needs randomness and hashing one needs a key, and both
 * live in `auth/magiccode.ts`.
 */

/** Crockford's base32: digits, then letters without I, L, O and U. */
export const MAGIC_CODE_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

/**
 * What somebody typed, as the code it stands for: upper-cased, with spaces and
 * dashes gone, and the letters people misread for digits read as those digits.
 * Anything else outside the alphabet is left in, so it fails to match rather
 * than being quietly dropped into a different code.
 */
export function normalizeMagicCode(input: string): string {
  return input.toUpperCase().replace(/[\s-]/g, "").replace(/O/g, "0").replace(/[IL]/g, "1");
}

/** `ABCD1234` as `ABCD-1234`, the way the email shows it. */
export function formatMagicCode(code: string): string {
  return `${code.slice(0, 4)}-${code.slice(4)}`;
}
