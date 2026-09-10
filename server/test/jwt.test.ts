import { describe, expect, it } from "vitest";
import {
  ACCESS_TOKEN_TTL_SECONDS,
  bearerToken,
  INVITE_TOKEN_TTL_SECONDS,
  signAccessToken,
  signInviteToken,
  verifyAccessToken,
  verifyInviteToken,
} from "../src/auth/jwt.js";
import { encodeUtf8Base64Url } from "../src/lib/base64url.js";

const KEY = "a-test-signing-key";
const NOW = Date.parse("2026-09-08T12:00:00.000Z");

describe("access tokens (L1)", () => {
  it("round-trips claims", async () => {
    const token = await signAccessToken({ sub: "user-1", deviceId: "device-1" }, KEY, NOW);
    const result = await verifyAccessToken(token, KEY, NOW);
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.claims.sub).toBe("user-1");
    expect(result.claims.deviceId).toBe("device-1");
    expect(result.claims.exp - result.claims.iat).toBe(ACCESS_TOKEN_TTL_SECONDS);
  });

  it("expires after 15 minutes", async () => {
    const token = await signAccessToken({ sub: "user-1", deviceId: "device-1" }, KEY, NOW);
    const justBefore = await verifyAccessToken(
      token,
      KEY,
      NOW + (ACCESS_TOKEN_TTL_SECONDS - 1) * 1000,
    );
    expect(justBefore.ok).toBe(true);

    const after = await verifyAccessToken(token, KEY, NOW + (ACCESS_TOKEN_TTL_SECONDS + 1) * 1000);
    expect(after).toEqual({ ok: false, reason: "expired" });
  });

  it("rejects a token signed with a different key", async () => {
    const token = await signAccessToken({ sub: "user-1", deviceId: "device-1" }, KEY, NOW);
    expect(await verifyAccessToken(token, "another-key", NOW)).toEqual({
      ok: false,
      reason: "bad-signature",
    });
  });

  it("rejects a tampered payload", async () => {
    const token = await signAccessToken({ sub: "user-1", deviceId: "device-1" }, KEY, NOW);
    const [header, , signature] = token.split(".") as [string, string, string];
    const forged = encodeUtf8Base64Url(
      JSON.stringify({ typ: "access", sub: "someone-else", deviceId: "d", iat: 0, exp: 9e9 }),
    );
    expect(await verifyAccessToken(`${header}.${forged}.${signature}`, KEY, NOW)).toEqual({
      ok: false,
      reason: "bad-signature",
    });
  });

  it('rejects "alg": "none", whatever the token claims about itself', async () => {
    // The classic JWT forgery. `alg` from the token is never used to pick an
    // algorithm — it is checked against the only one this server signs with.
    const header = encodeUtf8Base64Url(JSON.stringify({ alg: "none", typ: "JWT" }));
    const payload = encodeUtf8Base64Url(
      JSON.stringify({ typ: "access", sub: "attacker", deviceId: "d", iat: 0, exp: 9e9 }),
    );
    const result = await verifyAccessToken(`${header}.${payload}.`, KEY, NOW);
    expect(result.ok).toBe(false);
  });

  it("rejects a malformed token instead of throwing", async () => {
    for (const bad of ["", "a", "a.b", "a.b.c.d", "...", "not-a-token"]) {
      const result = await verifyAccessToken(bad, KEY, NOW);
      expect(result.ok).toBe(false);
    }
  });
});

describe("invite tokens (L3)", () => {
  it("round-trips and lasts 7 days", async () => {
    const token = await signInviteToken(
      { listId: "list-1", sub: "user-1", email: "invitee@dielys.test" },
      KEY,
      NOW,
    );
    const result = await verifyInviteToken(token, KEY, NOW);
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.claims.listId).toBe("list-1");
    expect(result.claims.email).toBe("invitee@dielys.test");
    expect(result.claims.exp - result.claims.iat).toBe(INVITE_TOKEN_TTL_SECONDS);
  });

  it("cannot be presented as an access token", async () => {
    // Same key, same signature algorithm — only the claim shape stops an
    // invite becoming a session (L3). This is the test that keeps it that way.
    const invite = await signInviteToken(
      { listId: "list-1", sub: "user-1", email: "invitee@dielys.test" },
      KEY,
      NOW,
    );
    expect(await verifyAccessToken(invite, KEY, NOW)).toEqual({ ok: false, reason: "wrong-type" });
  });

  it("an access token cannot be redeemed as an invite", async () => {
    const access = await signAccessToken({ sub: "user-1", deviceId: "device-1" }, KEY, NOW);
    expect(await verifyInviteToken(access, KEY, NOW)).toEqual({ ok: false, reason: "wrong-type" });
  });
});

describe("bearerToken", () => {
  it("reads a bearer header", () => {
    const request = new Request("https://x.test", { headers: { Authorization: "Bearer abc.def" } });
    expect(bearerToken(request)).toBe("abc.def");
  });

  it("ignores other schemes and a missing header", () => {
    expect(bearerToken(new Request("https://x.test"))).toBeNull();
    expect(
      bearerToken(new Request("https://x.test", { headers: { Authorization: "Basic abc" } })),
    ).toBeNull();
  });
});
