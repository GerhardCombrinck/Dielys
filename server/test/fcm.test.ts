import { afterEach, describe, expect, it, vi } from "vitest";
import {
  buildAssertion,
  FcmSender,
  parseServiceAccount,
  type ServiceAccount,
  wakeData,
} from "../src/push/fcm.js";

/**
 * The FCM client, without FCM.
 *
 * Two things here are worth a test and the rest is plumbing. The first is M1 —
 * a wake push carries three keys and no user content, and that is asserted on
 * the actual bytes handed to `fetch` rather than on the input. The second is
 * the OAuth2 assertion: it is a contract with somebody else's server, so a
 * wrong claim name or the wrong algorithm fails at 3 a.m. rather than at build
 * time unless something checks it here.
 *
 * `fetch` is stubbed on the global rather than mocked at the transport, because
 * what is being pinned down is the request this code composes.
 */

const TOKEN_URI = "https://oauth2.test/token";

interface Recorded {
  url: string;
  init: RequestInit | undefined;
}

/** A service account with a real RSA key, so the signature is a real one. */
async function serviceAccount(): Promise<ServiceAccount> {
  const pair = (await crypto.subtle.generateKey(
    {
      name: "RSASSA-PKCS1-v1_5",
      modulusLength: 2048,
      publicExponent: new Uint8Array([1, 0, 1]),
      hash: "SHA-256",
    },
    true,
    ["sign", "verify"],
  )) as CryptoKeyPair;

  // `exportKey` is typed as ArrayBuffer | JsonWebKey; "pkcs8" only ever gives
  // the former.
  const pkcs8 = new Uint8Array(
    (await crypto.subtle.exportKey("pkcs8", pair.privateKey)) as ArrayBuffer,
  );
  let binary = "";
  for (const byte of pkcs8) binary += String.fromCharCode(byte);
  const body = btoa(binary).replace(/(.{64})/g, "$1\n");

  privateKeys.set(
    `-----BEGIN PRIVATE KEY-----\n${body}\n-----END PRIVATE KEY-----\n`,
    pair.publicKey,
  );

  return {
    projectId: "dielys-test",
    clientEmail: "wake@dielys-test.iam.gserviceaccount.com",
    privateKey: `-----BEGIN PRIVATE KEY-----\n${body}\n-----END PRIVATE KEY-----\n`,
    tokenUri: TOKEN_URI,
  };
}

/** The verifying half of each generated pair, looked up by the PEM. */
const privateKeys = new Map<string, CryptoKey>();

/** Answers the token endpoint, and whatever `reply` says for a send. */
function stubFetch(reply: (url: string) => Response): Recorded[] {
  const calls: Recorded[] = [];
  vi.stubGlobal("fetch", (input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === "string" ? input : input.toString();
    calls.push({ url, init });
    if (url === TOKEN_URI) {
      return Promise.resolve(
        Response.json({ access_token: "ya29.test-access-token", expires_in: 3599 }),
      );
    }
    return Promise.resolve(reply(url));
  });
  return calls;
}

function sends(calls: Recorded[]): Array<Record<string, unknown>> {
  return calls
    .filter((call) => call.url !== TOKEN_URI)
    .map((call) => JSON.parse(String(call.init?.body)) as Record<string, unknown>);
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("service account parsing", () => {
  it("treats an unset credential as push-disabled, not an error", () => {
    // A deployment without FCM still syncs — over the socket while the app is
    // open, and on the half-hourly worker when it is not (H3.12). This is
    // deliberately unlike JWT_SIGNING_KEY, where absent means fail closed.
    expect(parseServiceAccount(undefined)).toBeNull();
    expect(parseServiceAccount("")).toBeNull();
    expect(parseServiceAccount("   ")).toBeNull();
  });

  it("returns null for anything that is not a usable account", () => {
    expect(parseServiceAccount("not json")).toBeNull();
    // What the test environment binds, and what an un-configured deployment
    // would have.
    expect(parseServiceAccount("{}")).toBeNull();
    expect(parseServiceAccount(JSON.stringify({ project_id: "p", client_email: "e" }))).toBeNull();
    expect(
      parseServiceAccount(JSON.stringify({ project_id: "p", client_email: "e", private_key: "" })),
    ).toBeNull();
  });

  it("defaults the token endpoint when the file omits it", () => {
    const account = parseServiceAccount(
      JSON.stringify({ project_id: "p", client_email: "e", private_key: "k" }),
    );
    expect(account?.tokenUri).toBe("https://oauth2.googleapis.com/token");
  });
});

describe("the OAuth2 assertion", () => {
  it("is an RS256 JWT that verifies against the account's key", async () => {
    const account = await serviceAccount();
    const assertion = await buildAssertion(account, 1_760_000_000_000);
    const [header, payload, signature] = assertion.split(".") as [string, string, string];

    expect(JSON.parse(decode(header))).toEqual({ alg: "RS256", typ: "JWT" });

    const claims = JSON.parse(decode(payload)) as Record<string, unknown>;
    expect(claims.iss).toBe(account.clientEmail);
    expect(claims.aud).toBe(TOKEN_URI);
    // The one scope FCM's send endpoint needs, and nothing wider.
    expect(claims.scope).toBe("https://www.googleapis.com/auth/firebase.messaging");
    expect(claims.exp).toBe((claims.iat as number) + 3600);

    const publicKey = privateKeys.get(account.privateKey);
    expect(publicKey).toBeDefined();
    const valid = await crypto.subtle.verify(
      "RSASSA-PKCS1-v1_5",
      publicKey as CryptoKey,
      bytes(signature),
      new TextEncoder().encode(`${header}.${payload}`),
    );
    expect(valid).toBe(true);
  });
});

describe("a wake push carries nothing but a hint to sync (M1)", () => {
  it("sends a data-only message with exactly three keys", async () => {
    const account = await serviceAccount();
    const calls = stubFetch(() => new Response(null, { status: 200 }));

    await new FcmSender(account).wake(
      [{ deviceId: "device-a", fcmToken: "token-a" }],
      wakeData("list-1", 7),
      Date.now(),
    );

    const [body] = sends(calls);
    const message = body?.message as Record<string, unknown>;
    expect(message.data).toEqual({ type: "sync", listId: "list-1", seq: "7" });
    // The rule this whole module exists to keep. A `notification` payload would
    // put text this system is not allowed to send onto a lock screen.
    expect("notification" in message).toBe(false);
    // No title, no list name, nothing a person typed — asserted on the bytes,
    // not on what was passed in.
    expect(JSON.stringify(body)).not.toMatch(/title|Groceries|notification/i);
  });

  it("asks for high priority so a dozing phone actually wakes", async () => {
    const account = await serviceAccount();
    const calls = stubFetch(() => new Response(null, { status: 200 }));

    await new FcmSender(account).wake(
      [{ deviceId: "device-a", fcmToken: "token-a" }],
      wakeData("list-1", 1),
      Date.now(),
    );

    const [body] = sends(calls);
    const message = body?.message as Record<string, unknown> | undefined;
    expect(message?.android).toEqual({
      priority: "high",
      ttl: "3600s",
      collapse_key: "dielys-sync",
    });
  });

  it("stringifies seq, because FCM's data map holds no numbers", () => {
    expect(wakeData("list-1", 12)).toEqual({ type: "sync", listId: "list-1", seq: "12" });
  });
});

describe("sending", () => {
  it("wakes every target and mints one access token for all of them", async () => {
    const account = await serviceAccount();
    const calls = stubFetch(() => new Response(null, { status: 200 }));
    const sender = new FcmSender(account);
    const now = Date.now();

    await sender.wake(
      [
        { deviceId: "device-a", fcmToken: "token-a" },
        { deviceId: "device-b", fcmToken: "token-b" },
      ],
      wakeData("list-1", 3),
      now,
    );
    await sender.wake([{ deviceId: "device-a", fcmToken: "token-a" }], wakeData("list-1", 4), now);

    expect(calls.filter((call) => call.url === TOKEN_URI)).toHaveLength(1);
    expect(sends(calls)).toHaveLength(3);
    expect(sends(calls)[0]?.message).toMatchObject({ token: "token-a" });
    expect(sends(calls)[1]?.message).toMatchObject({ token: "token-b" });
  });

  it("reports a 404 as a device that is gone", async () => {
    const account = await serviceAccount();
    stubFetch((url) => new Response(null, { status: url.includes("messages:send") ? 404 : 200 }));

    const gone = await new FcmSender(account).wake(
      [{ deviceId: "device-a", fcmToken: "stale" }],
      wakeData("list-1", 1),
      Date.now(),
    );

    // UNREGISTERED: the app was uninstalled or its data cleared. The row goes,
    // or every later write pays a round trip to be told the same thing.
    expect(gone).toEqual(["device-a"]);
  });

  it("keeps a device whose send merely failed", async () => {
    const account = await serviceAccount();
    stubFetch((url) => new Response(null, { status: url.includes("messages:send") ? 500 : 200 }));

    // A 400 or a 500 can be this server's own fault. Deleting a working token
    // over our bug would silently stop waking a phone that is fine.
    const gone = await new FcmSender(account).wake(
      [{ deviceId: "device-a", fcmToken: "token-a" }],
      wakeData("list-1", 1),
      Date.now(),
    );
    expect(gone).toEqual([]);
  });

  it("gives up quietly when the token exchange fails", async () => {
    const account = await serviceAccount();
    vi.stubGlobal("fetch", () => Promise.resolve(new Response(null, { status: 401 })));

    // A push is best-effort (H3.12). A broken credential must not surface as a
    // failed write, and must not throw at the Durable Object that called it.
    await expect(
      new FcmSender(account).wake(
        [{ deviceId: "device-a", fcmToken: "token-a" }],
        wakeData("list-1", 1),
        Date.now(),
      ),
    ).resolves.toEqual([]);
  });

  it("sends nothing at all when there is nobody to wake", async () => {
    const account = await serviceAccount();
    const calls = stubFetch(() => new Response(null, { status: 200 }));

    await new FcmSender(account).wake([], wakeData("list-1", 1), Date.now());
    // Not even a token: an empty fan-out is the common case once both phones
    // are on sockets, and it must cost nothing.
    expect(calls).toHaveLength(0);
  });
});

function bytes(base64url: string): Uint8Array {
  const padded = base64url.replace(/-/g, "+").replace(/_/g, "/");
  const binary = atob(padded.padEnd(Math.ceil(padded.length / 4) * 4, "="));
  const out = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) out[i] = binary.charCodeAt(i);
  return out;
}

function decode(base64url: string): string {
  return new TextDecoder().decode(bytes(base64url));
}
