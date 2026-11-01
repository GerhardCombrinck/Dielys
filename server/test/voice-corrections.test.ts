import { SELF } from "cloudflare:test";
import type { TokenPair, VoiceCorrectionsResponse } from "@dielys/protocol";
import { describe, expect, it } from "vitest";

/**
 * The shared voice library (ADR 0016, PROTOCOL.md "Voice corrections"), through
 * the real Worker and UsersRoom (H1): one library for every account, the most
 * submitted correction winning per mishearing, the latest on a tie.
 */

const ADMIN = "test-admin-token-not-used-anywhere-real";
const PASSWORD = "a-generated-password-long-enough";

let addresses = 0;
async function call(method: string, path: string, body?: unknown, token?: string) {
  addresses += 1;
  const headers: Record<string, string> = {
    "content-type": "application/json",
    "CF-Connecting-IP": `2001:db8::c${addresses.toString(16)}`,
  };
  if (token !== undefined) headers.Authorization = `Bearer ${token}`;
  const init: RequestInit = { method, headers };
  if (body !== undefined) init.body = JSON.stringify(body);
  return SELF.fetch(`https://dielys.test${path}`, init);
}

async function signedIn(): Promise<string> {
  const email = `voice-${crypto.randomUUID()}@dielys.test`;
  expect((await call("POST", "/admin/users", { email, password: PASSWORD }, ADMIN)).status).toBe(
    201,
  );
  const login = await call("POST", "/auth/login", {
    email,
    password: PASSWORD,
    deviceId: crypto.randomUUID(),
  });
  return ((await login.json()) as TokenPair).accessToken;
}

async function submit(token: string, heard: string, saved: string): Promise<void> {
  const response = await call(
    "POST",
    "/voice/corrections",
    { heard, saved, language: "af" },
    token,
  );
  expect(response.status).toBe(204);
}

describe("shared voice corrections", () => {
  it("answers every account with the most submitted correction, the latest on a tie", async () => {
    const mine = await signedIn();
    const hers = await signedIn();

    // Two submissions for Tamatiesous against one for Tamatie sous.
    await submit(mine, "Tomatiesauce", "Tamatiesous");
    await submit(hers, "tomatiesauce ", "Tamatie sous");
    await submit(hers, "Tomatiesauce", "Tamatiesous");

    // A one-each conflict: the latest wins.
    await submit(mine, "Com comer", "Komkomer");
    await submit(hers, "com comer", "Komkommer");

    const response = await call("GET", "/voice/corrections", undefined, mine);
    expect(response.status).toBe(200);
    const { corrections } = (await response.json()) as VoiceCorrectionsResponse;
    const saved = (heard: string) =>
      corrections.filter((c) => c.heard.trim().toLowerCase() === heard).map((c) => c.saved);
    expect(saved("tomatiesauce")).toEqual(["Tamatiesous"]);
    expect(saved("com comer")).toEqual(["Komkommer"]);
  });

  it("refuses a malformed record and an anonymous caller", async () => {
    const token = await signedIn();
    expect((await call("POST", "/voice/corrections", { heard: "x" }, token)).status).toBe(400);
    expect((await call("GET", "/voice/corrections")).status).toBe(401);
  });
});
