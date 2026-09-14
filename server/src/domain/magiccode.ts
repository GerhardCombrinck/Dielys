/**
 * How a typed sign-in code is read (ADR 0008). Pure: generating one needs
 * randomness and hashing one needs a key, and both live in `auth/magiccode.ts`.
 */

/**
 * What somebody typed, as the code it stands for: spaces and dashes gone.
 * Anything else is left in, so it fails to match rather than being quietly
 * turned into a different code.
 */
export function normalizeMagicCode(input: string): string {
  return input.replace(/[\s-]/g, "");
}
