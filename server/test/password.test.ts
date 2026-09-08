import { describe, expect, it } from "vitest";
import {
  generateRefreshToken,
  hashPassword,
  hashRefreshToken,
  PASSWORD_ITERATIONS,
  timingSafeEqual,
  verifyPassword,
} from "../src/auth/password.js";

describe("password hashing (L1)", () => {
  it("verifies the right password and rejects the wrong one", async () => {
    const stored = await hashPassword("correct horse battery staple");
    expect(await verifyPassword("correct horse battery staple", stored)).toBe(true);
    expect(await verifyPassword("correct horse battery stapl", stored)).toBe(false);
    expect(await verifyPassword("", stored)).toBe(false);
  });

  it("salts per user, so identical passwords do not share a hash", async () => {
    const a = await hashPassword("same password");
    const b = await hashPassword("same password");
    expect(a.salt).not.toBe(b.salt);
    expect(a.hash).not.toBe(b.hash);
  });

  it("stores the iteration count with the hash", async () => {
    // This is what lets PASSWORD_ITERATIONS be raised later without locking
    // anyone out — an old hash keeps verifying at the cost it was made with.
    const stored = await hashPassword("a password", 1000);
    expect(stored.iterations).toBe(1000);
    expect(await verifyPassword("a password", stored)).toBe(true);

    const current = await hashPassword("a password");
    expect(current.iterations).toBe(PASSWORD_ITERATIONS);
  });

  it("handles unicode passwords", async () => {
    const stored = await hashPassword("wagwoord-🍖-ë");
    expect(await verifyPassword("wagwoord-🍖-ë", stored)).toBe(true);
    expect(await verifyPassword("wagwoord-🍖-e", stored)).toBe(false);
  });

  it("does not throw on a corrupt stored salt", async () => {
    expect(
      await verifyPassword("x", { hash: "abc", salt: "!!!not base64!!!", iterations: 1000 }),
    ).toBe(false);
  });
});

describe("refresh tokens (L1)", () => {
  it("generates distinct 256-bit values", () => {
    const tokens = new Set(Array.from({ length: 50 }, () => generateRefreshToken()));
    expect(tokens.size).toBe(50);
    // 32 bytes as unpadded base64url.
    for (const token of tokens) expect(token.length).toBe(43);
  });

  it("hashes deterministically, and the hash is not the token", async () => {
    const token = generateRefreshToken();
    const hash = await hashRefreshToken(token);
    expect(await hashRefreshToken(token)).toBe(hash);
    expect(hash).not.toBe(token);
  });
});

describe("timingSafeEqual", () => {
  it("matches equal strings and rejects differences at any position", () => {
    expect(timingSafeEqual("abcdef", "abcdef")).toBe(true);
    expect(timingSafeEqual("abcdef", "abcdeg")).toBe(false);
    expect(timingSafeEqual("abcdef", "zbcdef")).toBe(false);
    expect(timingSafeEqual("abcdef", "abcde")).toBe(false);
    expect(timingSafeEqual("", "")).toBe(true);
  });
});
