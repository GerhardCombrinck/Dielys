import { afterEach, describe, expect, it, vi } from "vitest";
import {
  ApiError,
  isDefinitelySignedOut,
  onRefreshTokenRequested,
  refreshTokens,
  setAuthTokens,
} from "./client.js";

describe("ApiError", () => {
  it("carries the status and the server's stable code, not a message to parse", () => {
    const error = new ApiError(401, "unauthorized");
    expect(error.status).toBe(401);
    expect(error.code).toBe("unauthorized");
    expect(error).toBeInstanceOf(Error);
  });
});

describe("refreshTokens", () => {
  afterEach(() => {
    // Undo any registration a test left behind (module-level state, per
    // #83's cross-tab getter) so it cannot leak into a later, unrelated test.
    onRefreshTokenRequested(() => null);
  });

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

  it("does not sign out on a rate limit or a transient server error (#83)", async () => {
    // Only a definitive 401 means the refresh token is actually invalid —
    // anything else (429, a dropped connection, a 5xx) must leave the stored
    // token alone so the caller can retry with it later instead of forcing a
    // fresh sign-in.
    for (const status of [429, 500, 503]) {
      setAuthTokens({
        accessToken: "old-access",
        refreshToken: "old-refresh",
        deviceId: "device-1",
        userId: "user-1",
      });
      vi.stubGlobal(
        "fetch",
        vi.fn(async () => new Response(JSON.stringify({ code: "rate-limited" }), { status })),
      );

      try {
        const result = await refreshTokens();
        expect(result).toBeNull();
        expect(isDefinitelySignedOut()).toBe(false);
      } finally {
        vi.unstubAllGlobals();
        setAuthTokens(null);
      }
    }
  });

  it("signs out on a definitive 401 rejection", async () => {
    setAuthTokens({
      accessToken: "old-access",
      refreshToken: "old-refresh",
      deviceId: "device-1",
      userId: "user-1",
    });
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => new Response(JSON.stringify({ code: "token-expired" }), { status: 401 })),
    );

    try {
      const result = await refreshTokens();
      expect(result).toBeNull();
      expect(isDefinitelySignedOut()).toBe(true);
    } finally {
      vi.unstubAllGlobals();
      setAuthTokens(null);
    }
  });

  it("uses the freshest persisted refresh token, not a stale in-memory one", async () => {
    // Simulates the cross-tab race (#83): another tab already rotated the
    // token and wrote the new value to localStorage before this tab's own
    // refresh call went out.
    setAuthTokens({
      accessToken: "old-access",
      refreshToken: "stale-refresh",
      deviceId: "device-1",
      userId: "user-1",
    });
    onRefreshTokenRequested(() => "rotated-elsewhere-refresh");
    let sentRefreshToken: string | null = null;
    vi.stubGlobal(
      "fetch",
      vi.fn(async (_url: string, init: RequestInit) => {
        sentRefreshToken = (JSON.parse(init.body as string) as { refreshToken: string })
          .refreshToken;
        return new Response(
          JSON.stringify({
            accessToken: "new-access",
            refreshToken: "new-refresh",
            expiresIn: 900,
            userId: "user-1",
          }),
          { status: 200, headers: { "content-type": "application/json" } },
        );
      }),
    );

    try {
      await refreshTokens();
      expect(sentRefreshToken).toBe("rotated-elsewhere-refresh");
    } finally {
      vi.unstubAllGlobals();
      setAuthTokens(null);
    }
  });
});
