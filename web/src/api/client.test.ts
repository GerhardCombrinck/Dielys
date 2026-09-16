import { describe, expect, it, vi } from "vitest";
import { ApiError, refreshTokens, setAuthTokens } from "./client.js";

describe("ApiError", () => {
  it("carries the status and the server's stable code, not a message to parse", () => {
    const error = new ApiError(401, "unauthorized");
    expect(error.status).toBe(401);
    expect(error.code).toBe("unauthorized");
    expect(error).toBeInstanceOf(Error);
  });
});

describe("refreshTokens", () => {
  it("shares one in-flight request across concurrent callers", async () => {
    // A refresh token is single-use and rotates on every call (L1) — two
    // callers each spending it (React StrictMode's double effect-invoke in
    // dev, or two requests each hitting a 401 at once) would otherwise have
    // the loser present an already-rotated token, which the server treats as
    // theft and revokes the whole session.
    setAuthTokens({
      accessToken: "old-access",
      refreshToken: "old-refresh",
      deviceId: "device-1",
      userId: "user-1",
    });
    let calls = 0;
    const fetchMock = vi.fn(async () => {
      calls += 1;
      await new Promise((resolve) => setTimeout(resolve, 5));
      return new Response(
        JSON.stringify({
          accessToken: "new-access",
          refreshToken: "new-refresh",
          expiresIn: 900,
          userId: "user-1",
        }),
        { status: 200, headers: { "content-type": "application/json" } },
      );
    });
    vi.stubGlobal("fetch", fetchMock);

    try {
      const [a, b] = await Promise.all([refreshTokens(), refreshTokens()]);
      expect(calls).toBe(1);
      expect(a).toEqual(b);
      expect(a?.refreshToken).toBe("new-refresh");
    } finally {
      vi.unstubAllGlobals();
      setAuthTokens(null);
    }
  });

  it("allows a fresh request once the in-flight one settles", async () => {
    setAuthTokens({
      accessToken: "old-access",
      refreshToken: "old-refresh",
      deviceId: "device-1",
      userId: "user-1",
    });
    let calls = 0;
    const fetchMock = vi.fn(async () => {
      calls += 1;
      return new Response(
        JSON.stringify({
          accessToken: `access-${calls}`,
          refreshToken: `refresh-${calls}`,
          expiresIn: 900,
          userId: "user-1",
        }),
        { status: 200, headers: { "content-type": "application/json" } },
      );
    });
    vi.stubGlobal("fetch", fetchMock);

    try {
      await refreshTokens();
      await refreshTokens();
      expect(calls).toBe(2);
    } finally {
      vi.unstubAllGlobals();
      setAuthTokens(null);
    }
  });
});
