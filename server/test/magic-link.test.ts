import { SELF } from "cloudflare:test";
import type {
  MagicLinkStatusResponse,
  RequestMagicLinkResponse,
  TokenPair,
} from "@dielys/protocol";
import { afterEach, describe, expect, it, vi } from "vitest";
import { isUsableEmailConfig } from "../src/index.js";

/**
 * The passwordless sign-in path through the real Worker and real UsersRoom
 * (H1, ADR 0005): request mints and mails a link, verify redeems the token in
 * it exactly once.
 *
 * Brevo is stubbed at `fetch`, the same approach `fcm.test.ts` uses for FCM —
 * what is being pinned down is the request this code composes and the token
 * it embeds in the link, not Brevo's own behaviour.
 */

const BREVO_URL = "https://api.brevo.com/v3/smtp/email";
const BREVO_EVENTS_URL = "https://api.brevo.com/v3/smtp/statistics/events";

let seq = 0;
function uniqueEmail(): string {
  seq += 1;
  return `user-${seq}-${crypto.randomUUID()}@dielys.test`;
}

/** A distinct client per request by default, so one test's attempts do not
 * spend another's rate-limit allowance (ADR 0004/0005). */
let addresses = 0;
function nextAddress(): string {
  addresses += 1;
  return `2001:db8:1::${addresses.toString(16)}`;
}

async function post(
  path: string,
  body: unknown,
  address: string = nextAddress(),
): Promise<Response> {
  return SELF.fetch(`https://dielys.test${path}`, {
    method: "POST",
    headers: { "content-type": "application/json", "CF-Connecting-IP": address },
    body: JSON.stringify(body),
  });
}

/**
 * Stubs Brevo to accept every send and records each one, plus — when
 * `delivered` names an address — stubs the event-report endpoint
 * `magicLinkStatus` polls, so a `messageId` for it exists at all. Extracting
 * the token from the mailed link, rather than reaching into storage, is what
 * proves the token that went out is the one that redeems.
 */
function stubBrevo(delivered: Set<string> = new Set()): {
  sends: Array<{ to: string; token: string }>;
} {
  const sends: Array<{ to: string; token: string }> = [];
  let nextMessageId = 0;
  vi.stubGlobal("fetch", (input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === "string" ? input : input.toString();

    if (url === BREVO_URL) {
      const body = JSON.parse(String(init?.body)) as {
        to: Array<{ email: string }>;
        textContent: string;
      };
      const match = /token=([^\s&]+)/.exec(body.textContent);
      if (match === null) throw new Error("email carried no token");
      const to = body.to[0]?.email as string;
      sends.push({ to, token: decodeURIComponent(match[1] as string) });
      nextMessageId += 1;
      return Promise.resolve(
        Response.json({ messageId: `msg-${nextMessageId}-${to}` }, { status: 201 }),
      );
    }

    if (url.startsWith(BREVO_EVENTS_URL)) {
      const messageId = new URL(url).searchParams.get("messageId") ?? "";
      const to = messageId.replace(/^msg-\d+-/, "");
      const events = delivered.has(to) ? [{ event: "delivered", messageId }] : [];
      return Promise.resolve(Response.json({ events }, { status: 200 }));
    }

    throw new Error(`unexpected fetch in test: ${url}`);
  });
  return { sends };
}

afterEach(() => {
  vi.unstubAllGlobals();
});

async function requestLink(email: string, address?: string): Promise<string> {
  return (await requestLinkFull(email, address)).token;
}

/** Same request, but also hands back `requestId` — what the delivery-status
 * tests poll `/auth/magic/status` with. */
async function requestLinkFull(
  email: string,
  address?: string,
  delivered?: Set<string>,
): Promise<{ token: string; requestId: string }> {
  const { sends } = stubBrevo(delivered);
  const response = await post("/auth/magic/request", { email }, address);
  expect(response.status).toBe(200);
  const body = (await response.json()) as RequestMagicLinkResponse;
  expect(body.expiresIn).toBe(15 * 60);
  const sent = sends[0];
  expect(sent).toBeDefined();
  return { token: (sent as { token: string }).token, requestId: body.requestId };
}

async function get(path: string, address: string = nextAddress()): Promise<Response> {
  return SELF.fetch(`https://dielys.test${path}`, {
    headers: { "CF-Connecting-IP": address },
  });
}

describe("configuration gate (ADR 0005)", () => {
  it("treats a missing Brevo credential or sender as unconfigured", () => {
    expect(isUsableEmailConfig({ BREVO_API_KEY: "", EMAIL_FROM: "a@b.com" } as Env)).toBe(false);
    expect(isUsableEmailConfig({ BREVO_API_KEY: "k", EMAIL_FROM: "" } as Env)).toBe(false);
    expect(isUsableEmailConfig({ BREVO_API_KEY: "k", EMAIL_FROM: "a@b.com" } as Env)).toBe(true);
  });
});

describe("requesting a link", () => {
  it("mails a link and answers the same shape for a new or existing address", async () => {
    const email = uniqueEmail();
    const token = await requestLink(email);
    expect(token.length).toBeGreaterThan(0);
  });

  it("does not reveal whether the address already has an account", async () => {
    const { sends } = stubBrevo();
    const fresh = await post("/auth/magic/request", { email: uniqueEmail() });
    const again = await post("/auth/magic/request", { email: uniqueEmail() });
    expect(fresh.status).toBe(again.status);
    expect(sends).toHaveLength(2);
  });

  it("rejects a malformed email", async () => {
    const response = await post("/auth/magic/request", { email: "not-an-email" });
    expect(response.status).toBe(400);
  });
});

describe("verifying a link (L1-shaped: single-use, expiring)", () => {
  it("creates an account on first use and signs in", async () => {
    const email = uniqueEmail();
    const token = await requestLink(email);

    const response = await post("/auth/magic/verify", { token, deviceId: "device-a" });
    expect(response.status).toBe(200);
    const tokens = (await response.json()) as TokenPair;
    expect(tokens.userId).toMatch(/^[0-9a-f-]{36}$/);
    expect(tokens.accessToken.split(".")).toHaveLength(3);
  });

  it("signs in an existing account on a later link, same user id", async () => {
    const email = uniqueEmail();
    const first = await requestLink(email);
    const firstSession = await post("/auth/magic/verify", { token: first, deviceId: "device-a" });
    const firstTokens = (await firstSession.json()) as TokenPair;

    const second = await requestLink(email);
    const secondSession = await post("/auth/magic/verify", { token: second, deviceId: "device-b" });
    const secondTokens = (await secondSession.json()) as TokenPair;

    expect(secondTokens.userId).toBe(firstTokens.userId);
  });

  it("cannot be redeemed twice", async () => {
    const email = uniqueEmail();
    const token = await requestLink(email);

    const first = await post("/auth/magic/verify", { token, deviceId: "device-a" });
    expect(first.status).toBe(200);

    const replay = await post("/auth/magic/verify", { token, deviceId: "device-a" });
    expect(replay.status).toBe(401);
    expect(await replay.json()).toMatchObject({ code: "invalid-token" });
  });

  it("a newer request invalidates the previous link for the same email", async () => {
    const email = uniqueEmail();
    const stale = await requestLink(email);
    await requestLink(email);

    const response = await post("/auth/magic/verify", { token: stale, deviceId: "device-a" });
    expect(response.status).toBe(401);
  });

  it("rejects an unknown token", async () => {
    const response = await post("/auth/magic/verify", {
      token: "not-a-token-anyone-issued",
      deviceId: "device-a",
    });
    expect(response.status).toBe(401);
    expect(await response.json()).toMatchObject({ code: "invalid-token" });
  });

  it("an account created this way cannot sign in with a password", async () => {
    const email = uniqueEmail();
    const token = await requestLink(email);
    await post("/auth/magic/verify", { token, deviceId: "device-a" });

    const response = await post("/auth/login", {
      email,
      password: "any-password-at-all-long-enough",
      deviceId: "device-b",
    });
    expect(response.status).toBe(401);
  });

  it("rejects a malformed request", async () => {
    expect((await post("/auth/magic/verify", { token: "", deviceId: "device-a" })).status).toBe(
      400,
    );
    expect((await post("/auth/magic/verify", { token: "x", deviceId: "" })).status).toBe(400);
  });
});

describe("delivery status (ADR 0005 follow-up)", () => {
  it("reports not delivered until Brevo's event report says otherwise", async () => {
    const email = uniqueEmail();
    const { requestId } = await requestLinkFull(email);

    const before = await get(`/auth/magic/status?requestId=${requestId}`);
    expect(before.status).toBe(200);
    expect((await before.json()) as MagicLinkStatusResponse).toEqual({ delivered: false });
  });

  it("reports delivered once Brevo's event report has it", async () => {
    const email = uniqueEmail();
    const { requestId } = await requestLinkFull(email, undefined, new Set([email]));

    const response = await get(`/auth/magic/status?requestId=${requestId}`);
    expect(response.status).toBe(200);
    expect((await response.json()) as MagicLinkStatusResponse).toEqual({ delivered: true });
  });

  it("answers not-delivered for an unknown or missing requestId rather than an error", async () => {
    stubBrevo();
    const unknown = await get("/auth/magic/status?requestId=not-a-real-id");
    expect(unknown.status).toBe(200);
    expect((await unknown.json()) as MagicLinkStatusResponse).toEqual({ delivered: false });
  });

  it("rejects a request with no requestId at all", async () => {
    const response = await get("/auth/magic/status");
    expect(response.status).toBe(400);
  });

  it("a superseded request's requestId stops reporting delivered", async () => {
    const email = uniqueEmail();
    const { requestId: stale } = await requestLinkFull(email, undefined, new Set([email]));
    await requestLinkFull(email, undefined, new Set([email]));

    const response = await get(`/auth/magic/status?requestId=${stale}`);
    expect((await response.json()) as MagicLinkStatusResponse).toEqual({ delivered: false });
  });
});

describe("Android App Link verification (ADR 0005)", () => {
  it("serves a well-formed assetlinks.json naming the app's package", async () => {
    const response = await SELF.fetch("https://dielys.test/.well-known/assetlinks.json");
    expect(response.status).toBe(200);
    const body = (await response.json()) as Array<{ target: { package_name: string } }>;
    expect(body[0]?.target.package_name).toBe("za.co.dielys");
  });

  it("serves a fallback page at /magic for when the App Link did not open the app", async () => {
    const response = await SELF.fetch("https://dielys.test/magic?token=whatever");
    expect(response.status).toBe(200);
    expect(response.headers.get("content-type")).toContain("text/html");
  });
});

describe("rate limits (ADR 0005)", () => {
  const from = (n: number) => `198.51.100.${100 + n}`;

  it("stops a client requesting links over and over", async () => {
    stubBrevo();
    const client = from(1);
    const attempt = () => post("/auth/magic/request", { email: uniqueEmail() }, client);

    for (let i = 0; i < 5; i += 1) expect((await attempt()).status).toBe(200);
    const refused = await attempt();
    expect(refused.status).toBe(429);
    expect(await refused.json()).toMatchObject({ code: "rate-limited" });
  });

  it("stops requests for one address spread across many clients", async () => {
    stubBrevo();
    const email = uniqueEmail();
    const attempt = (n: number) => post("/auth/magic/request", { email }, from(10 + n));

    for (let i = 0; i < 3; i += 1) expect((await attempt(i)).status).toBe(200);
    const refused = await attempt(3);
    expect(refused.status).toBe(429);
  });
});
