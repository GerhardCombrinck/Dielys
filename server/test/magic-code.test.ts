import { SELF } from "cloudflare:test";
import type { TokenPair } from "@dielys/protocol";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { generateMagicCode } from "../src/auth/magiccode.js";
import { normalizeMagicCode } from "../src/domain/magiccode.js";

/**
 * The code mailed beside every magic link, and the Play review account
 * (ADR 0008), through the real Worker and UsersRoom (H1). Brevo is stubbed at
 * `fetch`, and the code a test types is the one read out of the email it sent.
 */

const BREVO_URL = "https://api.brevo.com/v3/smtp/email";
const REVIEW_EMAIL = "play-review@dielys.test";
const REVIEW_CODE = "202609141234";

let seq = 0;
function uniqueEmail(): string {
  seq += 1;
  return `code-${seq}-${crypto.randomUUID()}@dielys.test`;
}

let addresses = 0;
function nextAddress(): string {
  addresses += 1;
  return `2001:db8:8::${addresses.toString(16)}`;
}

function post(path: string, body: unknown): Promise<Response> {
  return SELF.fetch(`https://dielys.test${path}`, {
    method: "POST",
    headers: { "content-type": "application/json", "CF-Connecting-IP": nextAddress() },
    body: JSON.stringify(body),
  });
}

let mails: Array<{ to: string; token: string; code: string; html: string }> = [];

beforeEach(() => {
  mails = [];
  vi.stubGlobal("fetch", (input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === "string" ? input : input.toString();
    if (url !== BREVO_URL) throw new Error(`unexpected fetch in test: ${url}`);
    const body = JSON.parse(String(init?.body)) as {
      to: Array<{ email: string }>;
      textContent: string;
      htmlContent: string;
    };
    const token = /token=([^\s&]+)/.exec(body.textContent)?.[1];
    const code = /code in the app: ([0-9]+)/.exec(body.textContent)?.[1];
    if (token === undefined || code === undefined) throw new Error("email carried no link or code");
    mails.push({
      to: body.to[0]?.email as string,
      token: decodeURIComponent(token),
      code,
      html: body.htmlContent,
    });
    return Promise.resolve(Response.json({ messageId: "msg" }, { status: 201 }));
  });
});

afterEach(() => {
  vi.unstubAllGlobals();
});

async function requestCode(email: string): Promise<{ token: string; code: string }> {
  const response = await post("/auth/magic/request", { email });
  expect(response.status).toBe(200);
  const mail = mails.at(-1);
  expect(mail?.to).toBe(email);
  return mail as { token: string; code: string };
}

const verifyCode = (email: string, code: string, deviceId = "device-a") =>
  post("/auth/magic/verify-code", { email, code, deviceId });

/** Six digits that are not [code]. */
function wrongCode(code: string, nth = 0): string {
  const first = (Number(code[0]) + 1 + (nth % 9)) % 10;
  return `${first}${code.slice(1)}`;
}

describe("the code in the email (ADR 0008)", () => {
  it("is six digits, in both parts of the email", async () => {
    const { code } = await requestCode(uniqueEmail());
    expect(code).toMatch(/^[0-9]{6}$/);
    expect(mails[0]?.html).toContain(code);
  });

  it("signs in and creates the account, as tapping the link does", async () => {
    const email = uniqueEmail();
    const { code } = await requestCode(email);

    const response = await verifyCode(email, code);
    expect(response.status).toBe(200);
    const tokens = (await response.json()) as TokenPair;
    expect(tokens.accessToken.split(".")).toHaveLength(3);

    // The same account the link would have reached.
    const { token } = await requestCode(email);
    const viaLink = await post("/auth/magic/verify", { token, deviceId: "device-b" });
    expect(((await viaLink.json()) as TokenPair).userId).toBe(tokens.userId);
  });

  it("forgives spaces and dashes, and the address's case", async () => {
    const email = uniqueEmail();
    const { code } = await requestCode(email);
    const typed = ` ${code.slice(0, 3)} -${code.slice(3)} `;

    expect((await verifyCode(email.toUpperCase(), typed)).status).toBe(200);
  });

  it("shares one use with the link in the same email", async () => {
    const email = uniqueEmail();
    const first = await requestCode(email);
    expect((await verifyCode(email, first.code)).status).toBe(200);
    const linkAfterCode = await post("/auth/magic/verify", { token: first.token, deviceId: "d" });
    expect(linkAfterCode.status).toBe(401);

    const second = await requestCode(email);
    const link = await post("/auth/magic/verify", { token: second.token, deviceId: "d" });
    expect(link.status).toBe(200);
    expect((await verifyCode(email, second.code)).status).toBe(401);
  });

  it("only works for the address it was sent to", async () => {
    const { code } = await requestCode(uniqueEmail());
    const response = await verifyCode(uniqueEmail(), code);
    expect(response.status).toBe(401);
    expect(await response.json()).toMatchObject({ code: "invalid-token" });
  });

  it("survives four wrong codes, and the fifth burns the link too", async () => {
    const email = uniqueEmail();
    const first = await requestCode(email);
    for (let i = 0; i < 4; i++) {
      expect((await verifyCode(email, wrongCode(first.code))).status).toBe(401);
    }
    expect((await verifyCode(email, first.code)).status).toBe(200);

    const second = await requestCode(email);
    for (let i = 0; i < 5; i++) await verifyCode(email, wrongCode(second.code));
    expect((await verifyCode(email, second.code)).status).toBe(401);
    const link = await post("/auth/magic/verify", { token: second.token, deviceId: "d" });
    expect(link.status).toBe(401);
  });

  it("stops taking codes for an address after ten wrong ones, across links", async () => {
    const email = uniqueEmail();
    let wrong = 0;
    for (let link = 0; link < 3; link++) {
      const { code } = await requestCode(email);
      for (let i = 0; i < 4 && wrong < 10; i++, wrong++) {
        expect((await verifyCode(email, wrongCode(code, i))).status).toBe(401);
      }
    }
    expect(wrong).toBe(10);

    // Now even the right code is refused, the same way a wrong one would be.
    const { code, token } = mails.at(-1) as { code: string; token: string };
    const locked = await verifyCode(email, code);
    expect(locked.status).toBe(429);
    expect(await locked.json()).toMatchObject({ code: "rate-limited" });

    // The link in the same email is not a guess, and still works.
    const link = await post("/auth/magic/verify", { token, deviceId: "d" });
    expect(link.status).toBe(200);
  });

  it("does not count right codes towards that limit", async () => {
    const email = uniqueEmail();
    for (let i = 0; i < 2; i++) {
      const { code } = await requestCode(email);
      for (let j = 0; j < 3; j++) await verifyCode(email, wrongCode(code, j));
      expect((await verifyCode(email, code)).status).toBe(200);
    }
    // Six wrong and two right. Had the right ones counted, the limit would be
    // reached two codes sooner, and the ninth wrong code would be refused.
    const { code } = await requestCode(email);
    for (let j = 0; j < 4; j++) {
      expect((await verifyCode(email, wrongCode(code, j))).status).toBe(401);
    }
    expect((await verifyCode(email, code)).status).toBe(429);
  });

  it("rejects a malformed request", async () => {
    expect((await verifyCode("not-an-email", "123456")).status).toBe(400);
    expect((await verifyCode(uniqueEmail(), "  ")).status).toBe(400);
    expect((await verifyCode(uniqueEmail(), "A".repeat(65))).status).toBe(400);
  });
});

describe("the Play review account (ADR 0008)", () => {
  it("signs in with the fixed code, and is never mailed", async () => {
    const asked = await post("/auth/magic/request", { email: REVIEW_EMAIL });
    expect(asked.status).toBe(200);
    expect(mails).toHaveLength(0);

    const first = await verifyCode(
      REVIEW_EMAIL,
      `${REVIEW_CODE.slice(0, 6)} ${REVIEW_CODE.slice(6)}`,
    );
    expect(first.status).toBe(200);
    const again = await verifyCode(REVIEW_EMAIL, REVIEW_CODE, "device-b");
    expect(again.status).toBe(200);
    expect(((await again.json()) as TokenPair).userId).toBe(
      ((await first.json()) as TokenPair).userId,
    );
  });

  it("does not accept its code for any other address", async () => {
    const email = uniqueEmail();
    await requestCode(email);
    expect((await verifyCode(email, REVIEW_CODE)).status).toBe(401);
  });

  it("does not accept any other code for its address", async () => {
    expect((await verifyCode(REVIEW_EMAIL, "123456")).status).toBe(401);
  });
});

describe("minting and reading codes", () => {
  it("mints six digits, leading zeros and all", () => {
    const seen = new Set<string>();
    let leadingZero = false;
    for (let i = 0; i < 2000; i++) {
      const code = generateMagicCode();
      expect(code).toMatch(/^[0-9]{6}$/);
      if (code.startsWith("0")) leadingZero = true;
      seen.add(code);
    }
    // A million codes: 2000 draws repeat a few at most, and about 200 start
    // with a zero, so neither of these is left to chance in any real sense.
    expect(seen.size).toBeGreaterThan(1990);
    expect(leadingZero).toBe(true);
  });

  it("ignores spaces and dashes, and nothing else", () => {
    expect(normalizeMagicCode(" 997 218 ")).toBe("997218");
    expect(normalizeMagicCode("997-218")).toBe("997218");
    expect(normalizeMagicCode("99721O")).toBe("99721O");
  });
});
