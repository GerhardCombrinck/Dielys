import { SELF } from "cloudflare:test";
import type { AcceptInviteResponse, CreateInviteResponse, TokenPair } from "@dielys/protocol";
import { describe, expect, it } from "vitest";

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

async function post(path: string, body: unknown, token?: string): Promise<Response> {
  const headers: Record<string, string> = { "content-type": "application/json" };
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

  it("has no public registration endpoint (L2)", async () => {
    const response = await post("/auth/register", { email: uniqueEmail(), password: PASSWORD });
    expect(response.status).toBe(404);
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

    const minted = await post(`/lists/${listId}/invite`, {}, ownerTokens.accessToken);
    expect(minted.status).toBe(200);
    const invite = (await minted.json()) as CreateInviteResponse;
    expect(invite.expiresIn).toBe(7 * 24 * 60 * 60);

    const inviteeEmail = uniqueEmail();
    await createUser(inviteeEmail);
    const inviteeTokens = await login(inviteeEmail, "device-b");

    // Before accepting, the list is not theirs.
    expect((await get(`/lists/${listId}/changes?since=0`, inviteeTokens.accessToken)).status).toBe(
      403,
    );

    const accepted = await post(
      "/invites/accept",
      { inviteToken: invite.inviteToken },
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

  it("accepting twice is a no-op, not an error (L3)", async () => {
    const { tokens: ownerTokens, listId } = await ownerWithList();
    const minted = await post(`/lists/${listId}/invite`, {}, ownerTokens.accessToken);
    const invite = (await minted.json()) as CreateInviteResponse;

    const inviteeEmail = uniqueEmail();
    await createUser(inviteeEmail);
    const inviteeTokens = await login(inviteeEmail, "device-b");

    await post("/invites/accept", { inviteToken: invite.inviteToken }, inviteeTokens.accessToken);
    const again = await post(
      "/invites/accept",
      { inviteToken: invite.inviteToken },
      inviteeTokens.accessToken,
    );
    expect(again.status).toBe(200);
    expect(await again.json()).toMatchObject({ alreadyMember: true });
  });

  it("a non-owner member cannot mint further invites", async () => {
    const { tokens: ownerTokens, listId } = await ownerWithList();
    const minted = await post(`/lists/${listId}/invite`, {}, ownerTokens.accessToken);
    const invite = (await minted.json()) as CreateInviteResponse;

    const inviteeEmail = uniqueEmail();
    await createUser(inviteeEmail);
    const inviteeTokens = await login(inviteeEmail, "device-b");
    await post("/invites/accept", { inviteToken: invite.inviteToken }, inviteeTokens.accessToken);

    const reinvite = await post(`/lists/${listId}/invite`, {}, inviteeTokens.accessToken);
    expect(reinvite.status).toBe(403);
  });

  it("a non-member cannot mint an invite for someone else's list", async () => {
    const { listId } = await ownerWithList();
    const strangerEmail = uniqueEmail();
    await createUser(strangerEmail);
    const strangerTokens = await login(strangerEmail, "device-b");

    const response = await post(`/lists/${listId}/invite`, {}, strangerTokens.accessToken);
    expect(response.status).toBe(403);
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
    const minted = await post(`/lists/${listId}/invite`, {}, ownerTokens.accessToken);
    const invite = (await minted.json()) as CreateInviteResponse;

    const response = await post("/invites/accept", { inviteToken: invite.inviteToken });
    expect(response.status).toBe(401);
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
    const invite = (await (
      await post(`/lists/${listId}/invite`, {}, owner.accessToken)
    ).json()) as CreateInviteResponse;
    await post("/invites/accept", { inviteToken: invite.inviteToken }, partner.accessToken);

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
