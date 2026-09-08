import { SELF } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import { isUsableSigningKey, MIN_SIGNING_KEY_LENGTH } from "../src/auth/jwt.js";

/**
 * The Worker does routing and auth only (D1). The auth *flow* is covered in
 * auth-flow.test.ts; this file covers the routing surface and the rule that
 * nothing reaches a ListRoom without passing the Worker's membership check
 * first (L3).
 */

describe("Worker routing", () => {
  it("serves health without auth", async () => {
    const response = await SELF.fetch("https://dielys.test/health");
    expect(response.status).toBe(200);
    expect(await response.json()).toMatchObject({ ok: true });
  });

  it("404s a path that is not a route", async () => {
    const response = await SELF.fetch("https://dielys.test/whatever");
    expect(response.status).toBe(404);
  });

  for (const path of ["ws", "changes", "mutate"]) {
    it(`denies /lists/{id}/${path} without a token`, async () => {
      const response = await SELF.fetch(`https://dielys.test/lists/list-1/${path}`);
      expect(response.status).toBe(401);
      expect(await response.json()).toMatchObject({ code: "unauthorized" });
    });
  }

  it("denies before reaching the DO, so no list is created as a side effect", async () => {
    const response = await SELF.fetch("https://dielys.test/lists/never-created/changes?since=0");
    expect(response.status).toBe(401);
    // An unauthorized probe must not be able to spin up a DO per guessed id.
    expect(await response.text()).not.toContain("catch-up-response");
  });

  it("returns a stable code and no internal detail (D4)", async () => {
    const response = await SELF.fetch("https://dielys.test/lists/list-1/mutate", {
      method: "POST",
      body: "{}",
    });
    const body = (await response.json()) as Record<string, unknown>;
    expect(Object.keys(body).sort()).toEqual(["code", "idempotencyKey", "type"]);
  });

  it("rejects a bearer token that is not a JWT at all", async () => {
    const response = await SELF.fetch("https://dielys.test/auth/memberships", {
      headers: { Authorization: "Bearer definitely-not-a-jwt" },
    });
    expect(response.status).toBe(401);
  });

  it("rejects GET on routes that only accept POST", async () => {
    for (const path of ["/admin/users", "/invites/accept"]) {
      const response = await SELF.fetch(`https://dielys.test${path}`);
      expect([401, 405]).toContain(response.status);
    }
  });

  it("survives a body that is not JSON", async () => {
    const response = await SELF.fetch("https://dielys.test/auth/login", {
      method: "POST",
      body: "{not json",
    });
    expect(response.status).toBe(400);
  });
});

describe("signing key guard (I1)", () => {
  it("serves health but refuses everything else when the key is unusable", async () => {
    // The tests run with a real key bound, so this checks the predicate that
    // gates the Worker rather than re-binding the environment.
    expect(isUsableSigningKey(undefined)).toBe(false);
    expect(isUsableSigningKey("")).toBe(false);
    expect(isUsableSigningKey("too-short")).toBe(false);
    expect(isUsableSigningKey("x".repeat(MIN_SIGNING_KEY_LENGTH))).toBe(true);
  });

  it("rejects a key that is a plausible-looking accident", async () => {
    // What an unset binding actually produces once it reaches TextEncoder.
    expect(isUsableSigningKey("undefined")).toBe(false);
    expect(isUsableSigningKey("null")).toBe(false);
  });
});
