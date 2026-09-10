import { SELF } from "cloudflare:test";
import type { AcceptInviteResponse, CreateInviteResponse, TokenPair } from "@dielys/protocol";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

/**
 * The whole auth path through the real Worker and real UsersRoom (H1).
 *
 * Covers L1 (issuance, rotation, reuse detection), L2 (admin-only account
 * creation) and L3 (invite mint, accept, and the rule that ListRoom is only
 * reachable through a Worker membership check).
 */

const ADMIN = "test-admin-token-not-used-anywhere-real";
const PASSWORD = "a-generated-password-long-enough";

let seq = 0;
function uniqueEmail(): string {
  seq += 1;
  return `user-${seq}-${crypto.randomUUID()}@dielys.test`;
}

/**
 * A distinct client per request by default, so one test's attempts do not spend
 * another's rate-limit allowance (L2, ADR 0004). A test that means to trip a
 * limit passes the same address twice.
 */
let addresses = 0;
function nextAddress(): string {
  addresses += 1;
  return `2001:db8::${addresses.toString(16)}`;
}

async function post(
  path: string,
  body: unknown,
  token?: string,
  address: string = nextAddress(),
): Promise<Response> {
  const headers: Record<string, string> = {
    "content-type": "application/json",
    "CF-Connecting-IP": address,
  };
  if (token !== undefined) headers.Authorization = `Bearer ${token}`;
  return SELF.fetch(`https://dielys.test${path}`, {
    method: "POST",
    headers,
    body: JSON.stringify(body),
  });
}

async function get(path: string, token?: string): Promise<Response> {
  const headers: Record<string, string> = {};
  if (token !== undefined) headers.Authorization = `Bearer ${token}`;
  return SELF.fetch(`https://dielys.test${path}`, { headers });
}

async function createUser(email: string): Promise<string> {
  const response = await post("/admin/users", { email, password: PASSWORD }, ADMIN);
  expect(response.status).toBe(201);
  return ((await response.json()) as { userId: string }).userId;
}

async function login(email: string, deviceId = "device-a"): Promise<TokenPair> {
  const response = await post("/auth/login", { email, password: PASSWORD, deviceId });
  expect(response.status).toBe(200);
  return (await response.json()) as TokenPair;
}

const BREVO_URL = "https://api.brevo.com/v3/smtp/email";

/**
 * Invites are mailed directly now (no `inviteToken` in the HTTP response) —
 * every test that mints one needs Brevo stubbed, so it's installed for the
 * whole file rather than per describe block. Nothing else in this file
 * calls `fetch` (FCM is unconfigured in the test env, see vitest.config.ts),
 * so a stub that only understands the invite-send shape is safe file-wide.
 */
let inviteSends: Array<{ to: string; token: string }> = [];

beforeEach(() => {
  inviteSends = [];
  vi.stubGlobal("fetch", (input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === "string" ? input : input.toString();
    if (url === BREVO_URL) {
      const body = JSON.parse(String(init?.body)) as {
        to: Array<{ email: string }>;
        textContent: string;
      };
      const match = /[?&]t=([^\s&]+)/.exec(body.textContent);
      if (match === null) throw new Error("invite email carried no token");
      const to = body.to[0]?.email as string;
      inviteSends.push({ to, token: decodeURIComponent(match[1] as string) });
      return Promise.resolve(
        Response.json({ messageId: `msg-${inviteSends.length}` }, { status: 201 }),
      );
    }
    throw new Error(`unexpected fetch in test: ${url}`);
  });
});

afterEach(() => {
  vi.unstubAllGlobals();
});

/**
 * Mints an invite for `email` and returns the token Brevo actually mailed —
 * extracted from the stubbed send, the same "prove the token that went out
 * is the one that redeems" reasoning `magic-link.test.ts` uses.
 */
async function mintInvite(
  listId: string,
  ownerToken: string,
  email: string,
  listTitle = "Test list",
): Promise<{ status: number; expiresIn: number | null; token: string | null }> {
  const response = await post(`/lists/${listId}/invite`, { listId, email, listTitle }, ownerToken);
  const body = response.status === 200 ? ((await response.json()) as CreateInviteResponse) : null;
  const sent = inviteSends.find((s) => s.to === email.trim().toLowerCase());
  return {
    status: response.status,
    expiresIn: body?.expiresIn ?? null,
    token: sent?.token ?? null,
  };
}

describe("account creation (L2)", () => {
  it("creates an account with the admin token", async () => {
    const userId = await createUser(uniqueEmail());
    expect(userId).toMatch(/^[0-9a-f-]{36}$/);
  });

  it("refuses without the admin token", async () => {
    const response = await post("/admin/users", { email: uniqueEmail(), password: PASSWORD });
    expect(response.status).toBe(401);
  });

  it("refuses with the wrong admin token", async () => {
    const response = await post(
      "/admin/users",
      { email: uniqueEmail(), password: PASSWORD },
      "not-the-admin-token-but-same-len",
    );
    expect(response.status).toBe(401);
  });

  it("refuses a duplicate email, case-insensitively", async () => {
    const email = uniqueEmail();
    await createUser(email);
    const again = await post(
      "/admin/users",
      { email: email.toUpperCase(), password: PASSWORD },
      ADMIN,
    );
    // Two accounts differing only in case would be two accounts to their
    // owners' surprise.
    expect(again.status).toBe(409);
  });

  it("refuses a password under the minimum length", async () => {
    const response = await post("/admin/users", { email: uniqueEmail(), password: "short" }, ADMIN);
    expect(response.status).toBe(400);
  });
});

/** ADR 0004 replaced L2's admin-only registration with a public route. */
describe("public registration (L2, ADR 0004)", () => {
  it("creates the account and signs the caller in, in one call", async () => {
    const email = uniqueEmail();
    const response = await post("/auth/register", {
      email,
      password: PASSWORD,
      deviceId: "device-a",
    });

    expect(response.status).toBe(201);
    const tokens = (await response.json()) as TokenPair;
    expect(tokens.userId).toMatch(/^[0-9a-f-]{36}$/);
    expect(tokens.accessToken.split(".")).toHaveLength(3);

    // The session it handed back is a real one, not a placeholder.
    const memberships = await get("/auth/memberships", tokens.accessToken);
    expect(memberships.status).toBe(200);
  });

  it("enforces the minimum password length, where login does not", async () => {
    const response = await post("/auth/register", {
      email: uniqueEmail(),
      password: "short",
      deviceId: "device-a",
    });
    expect(response.status).toBe(400);
  });

  it("refuses an email that is already taken, case-insensitively", async () => {
    const email = uniqueEmail();
    await createUser(email);

    const response = await post("/auth/register", {
      email: email.toUpperCase(),
      password: PASSWORD,
      deviceId: "device-b",
    });
    // Knowingly an enumeration oracle, bounded by the rate limit — ADR 0004.
    expect(response.status).toBe(409);
  });

  it("signs in on an account made this way", async () => {
    const email = uniqueEmail();
    await post("/auth/register", { email, password: PASSWORD, deviceId: "device-a" });

    const tokens = await login(email, "device-b");
    expect(tokens.accessToken.split(".")).toHaveLength(3);
  });
});

describe("auth rate limits (L2, ADR 0004)", () => {
  /** Same address every time: one client, one bucket. */
  const from = (n: number) => `198.51.100.${n}`;

  it("stops a client registering over and over", async () => {
    const client = from(1);
    const attempt = () =>
      post(
        "/auth/register",
        { email: uniqueEmail(), password: PASSWORD, deviceId: "device-a" },
        undefined,
        client,
      );

    expect((await attempt()).status).toBe(201);
    expect((await attempt()).status).toBe(201);
    expect((await attempt()).status).toBe(201);

    const refused = await attempt();
    expect(refused.status).toBe(429);
    expect(((await refused.json()) as { code: string }).code).toBe("rate-limited");
  });

  it("does not spend one client's allowance on another's attempts", async () => {
    const one = from(2);
    const two = from(3);
    const register = (client: string) =>
      post(
        "/auth/register",
        { email: uniqueEmail(), password: PASSWORD, deviceId: "device-a" },
        undefined,
        client,
      );

    for (let i = 0; i < 3; i += 1) expect((await register(one)).status).toBe(201);
    expect((await register(one)).status).toBe(429);
    expect((await register(two)).status).toBe(201);
  });

  it("stops a client guessing passwords", async () => {
    const client = from(4);
    const email = uniqueEmail();
    await createUser(email);

    const guess = () =>
      post(
        "/auth/login",
        { email, password: "wrong-but-long-enough", deviceId: "d" },
        undefined,
        client,
      );

    for (let i = 0; i < 10; i += 1) expect((await guess()).status).toBe(401);

    const refused = await guess();
    expect(refused.status).toBe(429);
    // The right password does not get past the limit either — the point is to
    // stop spending CPU on this client, not to grade the guesses.
    const correct = await post(
      "/auth/login",
      { email, password: PASSWORD, deviceId: "d" },
      undefined,
      client,
    );
    expect(correct.status).toBe(429);
  });
});

describe("login (L1)", () => {
  it("issues an access and refresh token pair", async () => {
    const email = uniqueEmail();
    const userId = await createUser(email);
    const tokens = await login(email);

    expect(tokens.userId).toBe(userId);
    expect(tokens.expiresIn).toBe(15 * 60);
    expect(tokens.accessToken.split(".")).toHaveLength(3);
    expect(tokens.refreshToken).not.toBe(tokens.accessToken);
  });

  it("rejects a wrong password", async () => {
    const email = uniqueEmail();
    await createUser(email);
    const response = await post("/auth/login", {
      email,
      password: "the-wrong-password-entirely",
      deviceId: "device-a",
    });
    expect(response.status).toBe(401);
  });

  it("gives the same answer for a wrong password and an unknown account", async () => {
    const email = uniqueEmail();
    await createUser(email);

    const wrongPassword = await post("/auth/login", {
      email,
      password: "the-wrong-password-entirely",
      deviceId: "device-a",
    });
    const unknownAccount = await post("/auth/login", {
      email: uniqueEmail(),
      password: PASSWORD,
      deviceId: "device-a",
    });

    // The response must not be usable to enumerate which emails have accounts.
    expect(wrongPassword.status).toBe(unknownAccount.status);
    expect(await wrongPassword.json()).toEqual(await unknownAccount.json());
  });

  it("accepts the email in any case", async () => {
    const email = uniqueEmail();
    await createUser(email);
    const response = await post("/auth/login", {
      email: email.toUpperCase(),
      password: PASSWORD,
      deviceId: "device-a",
    });
    expect(response.status).toBe(200);
  });
});

describe("refresh token rotation (L1)", () => {
  it("rotates: the new token works and the old one does not", async () => {
    const email = uniqueEmail();
    await createUser(email);
    const first = await login(email);

    const rotated = await post("/auth/refresh", {
      refreshToken: first.refreshToken,
      deviceId: "device-a",
    });
    expect(rotated.status).toBe(200);
    const second = (await rotated.json()) as TokenPair;
    expect(second.refreshToken).not.toBe(first.refreshToken);

    const third = await post("/auth/refresh", {
      refreshToken: second.refreshToken,
      deviceId: "device-a",
    });
    expect(third.status).toBe(200);
  });

  it("treats a replayed token as compromise and revokes every session", async () => {
    const email = uniqueEmail();
    await createUser(email);
    const first = await login(email);

    const rotated = await post("/auth/refresh", {
      refreshToken: first.refreshToken,
      deviceId: "device-a",
    });
    const second = (await rotated.json()) as TokenPair;

    // The spent token comes back — either stolen, or a client replayed it.
    const replay = await post("/auth/refresh", {
      refreshToken: first.refreshToken,
      deviceId: "device-a",
    });
    expect(replay.status).toBe(401);
    expect(await replay.json()).toMatchObject({ code: "token-reused" });

    // L1: the response is not just a rejection — every refresh token for that
    // user is invalidated, including the one the legitimate client holds.
    const afterRevocation = await post("/auth/refresh", {
      refreshToken: second.refreshToken,
      deviceId: "device-a",
    });
    expect(afterRevocation.status).toBe(401);
  });

  it("rejects a refresh token presented by a different device", async () => {
    const email = uniqueEmail();
    await createUser(email);
    const tokens = await login(email, "device-a");

    const wrongDevice = await post("/auth/refresh", {
      refreshToken: tokens.refreshToken,
      deviceId: "device-b",
    });
    expect(wrongDevice.status).toBe(401);

    // Not treated as reuse: a client bug must not log the household out.
    const rightDevice = await post("/auth/refresh", {
      refreshToken: tokens.refreshToken,
      deviceId: "device-a",
    });
    expect(rightDevice.status).toBe(200);
  });

  it("rejects an unknown refresh token", async () => {
    const response = await post("/auth/refresh", {
      refreshToken: "not-a-token-anyone-issued",
      deviceId: "device-a",
    });
    expect(response.status).toBe(401);
  });

  it("keeps two devices' sessions independent", async () => {
    const email = uniqueEmail();
    await createUser(email);
    const phone = await login(email, "device-a");
    const tablet = await login(email, "device-b");

    expect(phone.refreshToken).not.toBe(tablet.refreshToken);
    const rotatePhone = await post("/auth/refresh", {
      refreshToken: phone.refreshToken,
      deviceId: "device-a",
    });
    expect(rotatePhone.status).toBe(200);

    // Rotating one device must not disturb the other.
    const rotateTablet = await post("/auth/refresh", {
      refreshToken: tablet.refreshToken,
      deviceId: "device-b",
    });
    expect(rotateTablet.status).toBe(200);
  });
});

describe("list access (L3)", () => {
  it("denies a list the caller is not a member of", async () => {
    const email = uniqueEmail();
    await createUser(email);
    const tokens = await login(email);

    const response = await get(`/lists/${crypto.randomUUID()}/changes?since=0`, tokens.accessToken);
    // 403, not 404: membership must not double as an existence oracle.
    expect(response.status).toBe(403);
  });

  it("allows a list after claiming it, and the DO is reachable", async () => {
    const email = uniqueEmail();
    await createUser(email);
    const tokens = await login(email);
    const listId = crypto.randomUUID();

    const claim = await post(`/lists/${listId}`, {}, tokens.accessToken);
    expect(claim.status).toBe(200);
    expect(await claim.json()).toMatchObject({ role: "owner", alreadyMember: false });

    const changes = await get(`/lists/${listId}/changes?since=0`, tokens.accessToken);
    expect(changes.status).toBe(200);
    expect(await changes.json()).toMatchObject({ type: "catch-up-response", changes: [] });
  });

  it("re-claiming a list you already own is a no-op", async () => {
    const email = uniqueEmail();
    await createUser(email);
    const tokens = await login(email);
    const listId = crypto.randomUUID();

    await post(`/lists/${listId}`, {}, tokens.accessToken);
    const again = await post(`/lists/${listId}`, {}, tokens.accessToken);
    expect(again.status).toBe(200);
    expect(await again.json()).toMatchObject({ alreadyMember: true });
  });

  it("refuses to claim a list someone else already owns", async () => {
    const owner = uniqueEmail();
    const stranger = uniqueEmail();
    await createUser(owner);
    await createUser(stranger);
    const ownerTokens = await login(owner);
    const strangerTokens = await login(stranger, "device-b");
    const listId = crypto.randomUUID();

    await post(`/lists/${listId}`, {}, ownerTokens.accessToken);
    const stolen = await post(`/lists/${listId}`, {}, strangerTokens.accessToken);
    expect(stolen.status).toBe(403);
  });

  it("rejects a request with no token, and one with a forged token", async () => {
    const listId = crypto.randomUUID();
    expect((await get(`/lists/${listId}/changes?since=0`)).status).toBe(401);
    expect((await get(`/lists/${listId}/changes?since=0`, "forged.token.here")).status).toBe(401);
  });
});

describe("invites (L3)", () => {
  async function ownerWithList() {
    const email = uniqueEmail();
    await createUser(email);
    const tokens = await login(email);
    const listId = crypto.randomUUID();
    await post(`/lists/${listId}`, {}, tokens.accessToken);
    return { tokens, listId };
  }

  it("owner mints an invite, invitee accepts and gains access", async () => {
    const { tokens: ownerTokens, listId } = await ownerWithList();
    const inviteeEmail = uniqueEmail();

    const invite = await mintInvite(listId, ownerTokens.accessToken, inviteeEmail);
    expect(invite.status).toBe(200);
    expect(invite.expiresIn).toBe(7 * 24 * 60 * 60);
    expect(invite.token).not.toBeNull();

    await createUser(inviteeEmail);
    const inviteeTokens = await login(inviteeEmail, "device-b");

    // Before accepting, the list is not theirs.
    expect((await get(`/lists/${listId}/changes?since=0`, inviteeTokens.accessToken)).status).toBe(
      403,
    );

    const accepted = await post(
      "/invites/accept",
      { inviteToken: invite.token },
      inviteeTokens.accessToken,
    );
    expect(accepted.status).toBe(200);
    expect((await accepted.json()) as AcceptInviteResponse).toMatchObject({
      listId,
      role: "member",
      alreadyMember: false,
    });

    expect((await get(`/lists/${listId}/changes?since=0`, inviteeTokens.accessToken)).status).toBe(
      200,
    );
  });

  it("only the invited email can accept — a different account gets 403", async () => {
    const { tokens: ownerTokens, listId } = await ownerWithList();
    const inviteeEmail = uniqueEmail();
    const invite = await mintInvite(listId, ownerTokens.accessToken, inviteeEmail);

    const strangerEmail = uniqueEmail();
    await createUser(strangerEmail);
    const strangerTokens = await login(strangerEmail, "device-b");

    const response = await post(
      "/invites/accept",
      { inviteToken: invite.token },
      strangerTokens.accessToken,
    );
    expect(response.status).toBe(403);

    // The invited address still works — the mismatch didn't burn the token.
    await createUser(inviteeEmail);
    const inviteeTokens = await login(inviteeEmail, "device-c");
    const accepted = await post(
      "/invites/accept",
      { inviteToken: invite.token },
      inviteeTokens.accessToken,
    );
    expect(accepted.status).toBe(200);
  });

  it("accepting twice is a no-op, not an error (L3)", async () => {
    const { tokens: ownerTokens, listId } = await ownerWithList();
    const inviteeEmail = uniqueEmail();
    const invite = await mintInvite(listId, ownerTokens.accessToken, inviteeEmail);

    await createUser(inviteeEmail);
    const inviteeTokens = await login(inviteeEmail, "device-b");

    await post("/invites/accept", { inviteToken: invite.token }, inviteeTokens.accessToken);
    const again = await post(
      "/invites/accept",
      { inviteToken: invite.token },
      inviteeTokens.accessToken,
    );
    expect(again.status).toBe(200);
    expect(await again.json()).toMatchObject({ alreadyMember: true });
  });

  it("a non-owner member cannot mint further invites", async () => {
    const { tokens: ownerTokens, listId } = await ownerWithList();
    const inviteeEmail = uniqueEmail();
    const invite = await mintInvite(listId, ownerTokens.accessToken, inviteeEmail);

    await createUser(inviteeEmail);
    const inviteeTokens = await login(inviteeEmail, "device-b");
    await post("/invites/accept", { inviteToken: invite.token }, inviteeTokens.accessToken);

    const reinvite = await post(
      `/lists/${listId}/invite`,
      { listId, email: uniqueEmail(), listTitle: "Test list" },
      inviteeTokens.accessToken,
    );
    expect(reinvite.status).toBe(403);
  });

  it("a non-member cannot mint an invite for someone else's list", async () => {
    const { listId } = await ownerWithList();
    const strangerEmail = uniqueEmail();
    await createUser(strangerEmail);
    const strangerTokens = await login(strangerEmail, "device-b");

    const response = await post(
      `/lists/${listId}/invite`,
      { listId, email: uniqueEmail(), listTitle: "Test list" },
      strangerTokens.accessToken,
    );
    expect(response.status).toBe(403);
  });

  it("rejects a malformed recipient email or an oversized list title", async () => {
    const { tokens: ownerTokens, listId } = await ownerWithList();

    const badEmail = await post(
      `/lists/${listId}/invite`,
      { listId, email: "not-an-email", listTitle: "Test list" },
      ownerTokens.accessToken,
    );
    expect(badEmail.status).toBe(400);

    const badTitle = await post(
      `/lists/${listId}/invite`,
      { listId, email: uniqueEmail(), listTitle: "x".repeat(1001) },
      ownerTokens.accessToken,
    );
    expect(badTitle.status).toBe(400);
  });

  it("mails the invite as both HTML and plain text, carrying the list title and the link", async () => {
    const { tokens: ownerTokens, listId } = await ownerWithList();
    const inviteeEmail = uniqueEmail();

    interface CapturedSend {
      subject: string;
      textContent: string;
      htmlContent: string;
    }
    const captured: { value: CapturedSend | null } = { value: null };
    vi.stubGlobal("fetch", (_input: RequestInfo | URL, init?: RequestInit) => {
      captured.value = JSON.parse(String(init?.body)) as CapturedSend;
      return Promise.resolve(Response.json({ messageId: "msg-1" }, { status: 201 }));
    });

    const response = await post(
      `/lists/${listId}/invite`,
      { listId, email: inviteeEmail, listTitle: "Boodskappies" },
      ownerTokens.accessToken,
    );
    expect(response.status).toBe(200);

    expect(captured.value).not.toBeNull();
    expect(captured.value?.subject).toContain("Boodskappies");
    expect(captured.value?.textContent).toContain("Boodskappies");
    expect(captured.value?.textContent).toMatch(/https:\/\/dielys\.com\/invite\?t=/);
    expect(captured.value?.htmlContent).toContain("Boodskappies");
    expect(captured.value?.htmlContent).toMatch(/https:\/\/dielys\.com\/invite\?t=/);
  });

  it("fails loud, not silent, when the mail send is rejected", async () => {
    const { tokens: ownerTokens, listId } = await ownerWithList();
    vi.stubGlobal("fetch", () => Promise.resolve(new Response("nope", { status: 400 })));

    const response = await post(
      `/lists/${listId}/invite`,
      { listId, email: uniqueEmail(), listTitle: "Test list" },
      ownerTokens.accessToken,
    );
    expect(response.status).toBe(503);
    expect(await response.json()).toMatchObject({ code: "internal" });
  });

  it("an access token cannot be redeemed as an invite", async () => {
    const email = uniqueEmail();
    await createUser(email);
    const tokens = await login(email);

    const response = await post(
      "/invites/accept",
      { inviteToken: tokens.accessToken },
      tokens.accessToken,
    );
    // Signed with the same key — only the distinct claim shape stops this.
    expect(response.status).toBe(401);
  });

  it("accepting requires being logged in as somebody", async () => {
    const { tokens: ownerTokens, listId } = await ownerWithList();
    const invite = await mintInvite(listId, ownerTokens.accessToken, uniqueEmail());

    const response = await post("/invites/accept", { inviteToken: invite.token });
    expect(response.status).toBe(401);
  });

  it("serves a fallback page at /invite for when the App Link did not open the app", async () => {
    const response = await SELF.fetch("https://dielys.test/invite?t=whatever");
    expect(response.status).toBe(200);
    expect(response.headers.get("content-type")).toContain("text/html");
  });
});

describe("invite email rate limits", () => {
  async function ownerWithList() {
    const email = uniqueEmail();
    await createUser(email);
    const tokens = await login(email);
    const listId = crypto.randomUUID();
    await post(`/lists/${listId}`, {}, tokens.accessToken);
    return { tokens, listId };
  }

  it("stops one list mailing invites over and over", async () => {
    const { tokens: ownerTokens, listId } = await ownerWithList();
    const attempt = () =>
      post(
        `/lists/${listId}/invite`,
        { listId, email: uniqueEmail(), listTitle: "Test list" },
        ownerTokens.accessToken,
      );

    for (let i = 0; i < 10; i += 1) expect((await attempt()).status).toBe(200);
    const refused = await attempt();
    expect(refused.status).toBe(429);
    expect(await refused.json()).toMatchObject({ code: "rate-limited" });
  });

  it("stops invites for one recipient spread across many lists", async () => {
    const ownerEmail = uniqueEmail();
    await createUser(ownerEmail);
    const ownerTokens = await login(ownerEmail);
    const recipient = uniqueEmail();
    const attempt = async () => {
      const listId = crypto.randomUUID();
      await post(`/lists/${listId}`, {}, ownerTokens.accessToken);
      return post(
        `/lists/${listId}/invite`,
        { listId, email: recipient, listTitle: "Test list" },
        ownerTokens.accessToken,
      );
    };

    for (let i = 0; i < 5; i += 1) expect((await attempt()).status).toBe(200);
    const refused = await attempt();
    expect(refused.status).toBe(429);
  });
});

describe("memberships", () => {
  it("lists what the caller can reach", async () => {
    const email = uniqueEmail();
    await createUser(email);
    const tokens = await login(email);
    const first = crypto.randomUUID();
    const second = crypto.randomUUID();
    await post(`/lists/${first}`, {}, tokens.accessToken);
    await post(`/lists/${second}`, {}, tokens.accessToken);

    const response = await get("/auth/memberships", tokens.accessToken);
    expect(response.status).toBe(200);
    const body = (await response.json()) as { memberships: Array<{ listId: string }> };
    expect(body.memberships.map((m) => m.listId).sort()).toEqual([first, second].sort());
  });

  it("requires a token", async () => {
    expect((await get("/auth/memberships")).status).toBe(401);
  });

  it("orders by the caller's own position, unpositioned lists last and oldest first", async () => {
    const email = uniqueEmail();
    await createUser(email);
    const tokens = await login(email);
    const first = crypto.randomUUID();
    const second = crypto.randomUUID();
    const third = crypto.randomUUID();
    for (const listId of [first, second, third]) {
      await post(`/lists/${listId}`, {}, tokens.accessToken);
    }

    // Only the third is dragged, to the top.
    const moved = await post(
      "/auth/memberships/position",
      { listId: third, position: "a0" },
      tokens.accessToken,
    );
    expect(moved.status).toBe(200);
    expect(await moved.json()).toEqual({ listId: third, position: "a0" });

    const body = (await (await get("/auth/memberships", tokens.accessToken)).json()) as {
      memberships: Array<{ listId: string; position: string | null }>;
    };
    expect(body.memberships.map((m) => m.listId)).toEqual([third, first, second]);
    expect(body.memberships.map((m) => m.position)).toEqual(["a0", null, null]);
  });

  it("keeps one member's order to themselves", async () => {
    const ownerEmail = uniqueEmail();
    const partnerEmail = uniqueEmail();
    await createUser(ownerEmail);
    await createUser(partnerEmail);
    const owner = await login(ownerEmail);
    const partner = await login(partnerEmail);

    const listId = crypto.randomUUID();
    await post(`/lists/${listId}`, {}, owner.accessToken);
    const invite = await mintInvite(listId, owner.accessToken, partnerEmail);
    await post("/invites/accept", { inviteToken: invite.token }, partner.accessToken);

    await post("/auth/memberships/position", { listId, position: "a0" }, owner.accessToken);

    const partnerLists = (await (await get("/auth/memberships", partner.accessToken)).json()) as {
      memberships: Array<{ listId: string; position: string | null }>;
    };
    expect(partnerLists.memberships).toEqual([{ listId, role: "member", position: null }]);
  });

  it("refuses a list the caller is not on with 403, not 404", async () => {
    const email = uniqueEmail();
    await createUser(email);
    const tokens = await login(email);

    const response = await post(
      "/auth/memberships/position",
      { listId: crypto.randomUUID(), position: "a0" },
      tokens.accessToken,
    );
    expect(response.status).toBe(403);
  });

  it("rejects a position that is empty or over the bound", async () => {
    const email = uniqueEmail();
    await createUser(email);
    const tokens = await login(email);
    const listId = crypto.randomUUID();
    await post(`/lists/${listId}`, {}, tokens.accessToken);

    for (const position of ["", "a".repeat(257)]) {
      const response = await post(
        "/auth/memberships/position",
        { listId, position },
        tokens.accessToken,
      );
      expect(response.status).toBe(400);
    }
  });

  it("needs a token", async () => {
    expect((await post("/auth/memberships/position", { listId: "x", position: "a0" })).status).toBe(
      401,
    );
  });
});

describe("end to end: two people, one list", () => {
  it("a mutation by one member is visible to the other", async () => {
    const ownerEmail = uniqueEmail();
    const partnerEmail = uniqueEmail();
    await createUser(ownerEmail);
    await createUser(partnerEmail);
    const owner = await login(ownerEmail, "device-a");
    const partner = await login(partnerEmail, "device-b");

    const listId = crypto.randomUUID();
    await post(`/lists/${listId}`, {}, owner.accessToken);
    const invite = await mintInvite(listId, owner.accessToken, partnerEmail);
    await post("/invites/accept", { inviteToken: invite.token }, partner.accessToken);

    const mutation = await post(
      `/lists/${listId}/mutate`,
      {
        type: "mutate",
        protocolVersion: 2,
        listId,
        entityType: "task",
        entityId: crypto.randomUUID(),
        idempotencyKey: crypto.randomUUID(),
        deviceId: "device-a",
        patch: { title: "Melk", position: "a0" },
      },
      owner.accessToken,
    );
    expect(mutation.status).toBe(200);

    const partnerView = await get(`/lists/${listId}/changes?since=0`, partner.accessToken);
    expect(partnerView.status).toBe(200);
    const body = (await partnerView.json()) as { changes: Array<{ entity: { title: string } }> };
    expect(body.changes).toHaveLength(1);
    expect(body.changes[0]?.entity.title).toBe("Melk");
  });
});
