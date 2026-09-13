import { env, SELF } from "cloudflare:test";
import type { ListMembersResponse, MembershipsResponse, TokenPair } from "@dielys/protocol";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { usersRoom } from "../src/do/rooms.js";

/**
 * `DELETE /account` (ADR 0007) through the real Worker, UsersRoom and
 * ListRooms (H1): the account is erased, owned shared lists pass on, lists
 * nobody is left on are erased, and nothing the deleted account held keeps
 * working.
 */

const ADMIN = "test-admin-token-not-used-anywhere-real";
const PASSWORD = "a-generated-password-long-enough";
const BREVO_URL = "https://api.brevo.com/v3/smtp/email";

let seq = 0;
function uniqueEmail(): string {
  seq += 1;
  return `gone-${seq}-${crypto.randomUUID()}@dielys.test`;
}

let addresses = 0;
function nextAddress(): string {
  addresses += 1;
  return `2001:db8:7::${addresses.toString(16)}`;
}

function request(path: string, method: string, token?: string, body?: unknown): Promise<Response> {
  const headers: Record<string, string> = { "CF-Connecting-IP": nextAddress() };
  if (token !== undefined) headers.Authorization = `Bearer ${token}`;
  if (body !== undefined) headers["content-type"] = "application/json";
  const init: RequestInit = { method, headers };
  if (body !== undefined) init.body = JSON.stringify(body);
  return SELF.fetch(`https://dielys.test${path}`, init);
}

const post = (path: string, body: unknown, token?: string) => request(path, "POST", token, body);
const get = (path: string, token: string) => request(path, "GET", token);
const deleteAccount = (token?: string) => request("/account", "DELETE", token);

interface Person {
  email: string;
  userId: string;
  deviceId: string;
  tokens: TokenPair;
}

async function person(deviceId = `device-${crypto.randomUUID()}`): Promise<Person> {
  const email = uniqueEmail();
  const created = await post("/admin/users", { email, password: PASSWORD }, ADMIN);
  expect(created.status).toBe(201);
  const { userId } = (await created.json()) as { userId: string };
  const login = await post("/auth/login", { email, password: PASSWORD, deviceId });
  expect(login.status).toBe(200);
  return { email, userId, deviceId, tokens: (await login.json()) as TokenPair };
}

let inviteTokens = new Map<string, string>();
/** Every "confirm deleting your account" email, with the link it carried. */
let deletionMails: Array<{ to: string; link: URL }> = [];

beforeEach(() => {
  inviteTokens = new Map();
  deletionMails = [];
  vi.stubGlobal("fetch", (input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === "string" ? input : input.toString();
    if (url !== BREVO_URL) throw new Error(`unexpected fetch in test: ${url}`);
    const body = JSON.parse(String(init?.body)) as {
      to: Array<{ email: string }>;
      textContent: string;
    };
    const to = body.to[0]?.email as string;
    const deletion = /(https?:\/\/\S+\/account\/delete\/confirm\S*)/.exec(body.textContent);
    if (deletion !== null) {
      deletionMails.push({ to, link: new URL(deletion[1] as string) });
    } else {
      const match = /[?&]t=([^\s&]+)/.exec(body.textContent);
      if (match === null) throw new Error("email carried no recognisable link");
      inviteTokens.set(to, decodeURIComponent(match[1] as string));
    }
    return Promise.resolve(Response.json({ messageId: "msg" }, { status: 201 }));
  });
});

afterEach(() => {
  vi.unstubAllGlobals();
});

/** A list [owner] claims and names, so its room has something to lose. */
async function ownedList(owner: Person, title = "Inkopies"): Promise<string> {
  const listId = crypto.randomUUID();
  expect((await post(`/lists/${listId}`, {}, owner.tokens.accessToken)).status).toBe(200);
  const named = await post(
    `/lists/${listId}/mutate`,
    {
      type: "mutate",
      protocolVersion: 2,
      listId,
      entityType: "list",
      entityId: listId,
      idempotencyKey: crypto.randomUUID(),
      deviceId: owner.deviceId,
      patch: { title },
    },
    owner.tokens.accessToken,
  );
  expect(named.status).toBe(200);
  return listId;
}

async function invite(listId: string, owner: Person, email: string): Promise<string> {
  const sent = await post(
    `/lists/${listId}/invite`,
    { listId, email, listTitle: "Inkopies" },
    owner.tokens.accessToken,
  );
  expect(sent.status).toBe(200);
  return inviteTokens.get(email) as string;
}

async function join(listId: string, owner: Person, member: Person): Promise<void> {
  const token = await invite(listId, owner, member.email);
  const accepted = await post("/invites/accept", { inviteToken: token }, member.tokens.accessToken);
  expect(accepted.status).toBe(200);
}

async function changeCount(listId: string, token: string): Promise<number> {
  const response = await get(`/lists/${listId}/changes?since=0`, token);
  expect(response.status).toBe(200);
  return ((await response.json()) as { changes: unknown[] }).changes.length;
}

describe("deleting an account (ADR 0007)", () => {
  it("needs a token, and only answers DELETE", async () => {
    expect((await deleteAccount()).status).toBe(401);
    const someone = await person();
    expect((await get("/account", someone.tokens.accessToken)).status).toBe(405);
  });

  it("ends every session and frees the email address", async () => {
    const gone = await person();

    expect((await deleteAccount(gone.tokens.accessToken)).status).toBe(204);

    const refresh = await post("/auth/refresh", {
      refreshToken: gone.tokens.refreshToken,
      deviceId: gone.deviceId,
    });
    expect(refresh.status).toBe(401);

    const login = await post("/auth/login", {
      email: gone.email,
      password: PASSWORD,
      deviceId: gone.deviceId,
    });
    expect(login.status).toBe(401);

    // The same address is a stranger now: a new account, not the old one back.
    const again = await post("/auth/register", {
      email: gone.email,
      password: PASSWORD,
      deviceId: gone.deviceId,
    });
    expect(again.status).toBe(201);
    const fresh = (await again.json()) as TokenPair;
    expect(fresh.userId).not.toBe(gone.userId);
    const memberships = await get("/auth/memberships", fresh.accessToken);
    expect(((await memberships.json()) as MembershipsResponse).memberships).toEqual([]);
  });

  it("answers a repeat as done, so a retry after a lost response is safe", async () => {
    const gone = await person();
    expect((await deleteAccount(gone.tokens.accessToken)).status).toBe(204);
    // The access token is still inside its lifetime; the account behind it is not.
    expect((await deleteAccount(gone.tokens.accessToken)).status).toBe(204);
  });

  it("erases a list nobody else is on, room and all", async () => {
    const gone = await person();
    const listId = await ownedList(gone);

    expect((await deleteAccount(gone.tokens.accessToken)).status).toBe(204);

    // Nobody holds the id any more, so a stranger can claim it — and finds
    // nothing of what was there.
    const stranger = await person();
    expect((await post(`/lists/${listId}`, {}, stranger.tokens.accessToken)).status).toBe(200);
    expect(await changeCount(listId, stranger.tokens.accessToken)).toBe(0);
  });

  it("hands an owned shared list to whoever has been on it longest", async () => {
    const owner = await person();
    const first = await person();
    const second = await person();
    const listId = await ownedList(owner);
    await join(listId, owner, first);
    await join(listId, owner, second);

    expect((await deleteAccount(owner.tokens.accessToken)).status).toBe(204);

    const response = await get(`/lists/${listId}/members`, first.tokens.accessToken);
    const members = ((await response.json()) as ListMembersResponse).members;
    expect(members.map((m) => [m.userId, m.role])).toEqual([
      [first.userId, "owner"],
      [second.userId, "member"],
    ]);

    // The list itself is untouched, and the new owner can do what owners do.
    expect(await changeCount(listId, second.tokens.accessToken)).toBe(1);
    const invited = await post(
      `/lists/${listId}/invite`,
      { listId, email: uniqueEmail(), listTitle: "Inkopies" },
      first.tokens.accessToken,
    );
    expect(invited.status).toBe(200);
  });

  it("takes a member off someone else's list and leaves the list alone", async () => {
    const owner = await person();
    const gone = await person();
    const listId = await ownedList(owner);
    await join(listId, owner, gone);

    expect((await deleteAccount(gone.tokens.accessToken)).status).toBe(204);

    const response = await get(`/lists/${listId}/members`, owner.tokens.accessToken);
    const members = ((await response.json()) as ListMembersResponse).members;
    expect(members.map((m) => m.userId)).toEqual([owner.userId]);
    expect(await changeCount(listId, owner.tokens.accessToken)).toBe(1);

    // Still inside its lifetime, the deleted account's access token reaches nothing.
    expect((await get(`/lists/${listId}/changes?since=0`, gone.tokens.accessToken)).status).toBe(
      403,
    );
  });

  it("forgets the account's push tokens", async () => {
    const owner = await person();
    const gone = await person();
    const listId = await ownedList(owner);
    await join(listId, owner, gone);
    const registered = await post(
      "/devices/token",
      { fcmToken: "fcm-token-of-the-deleted-phone" },
      gone.tokens.accessToken,
    );
    expect(registered.status).toBe(204);

    await deleteAccount(gone.tokens.accessToken);

    const devices = await usersRoom(env).devicesForList(listId);
    expect(devices.map((d) => d.deviceId)).not.toContain(gone.deviceId);
  });

  it("refuses an invite to a list nobody is on any more", async () => {
    const gone = await person();
    const listId = await ownedList(gone);
    const invitee = await person();
    const token = await invite(listId, gone, invitee.email);

    await deleteAccount(gone.tokens.accessToken);

    const accepted = await post(
      "/invites/accept",
      { inviteToken: token },
      invitee.tokens.accessToken,
    );
    expect(accepted.status).toBe(403);
    expect((await get(`/lists/${listId}/changes?since=0`, invitee.tokens.accessToken)).status).toBe(
      403,
    );
  });

  it("closes the sockets on a shared list so they re-authorize", async () => {
    const owner = await person();
    const member = await person();
    const listId = await ownedList(owner);
    await join(listId, owner, member);

    const upgrade = await SELF.fetch(
      `https://dielys.test/lists/${listId}/ws?deviceId=${member.deviceId}`,
      { headers: { Upgrade: "websocket", Authorization: `Bearer ${member.tokens.accessToken}` } },
    );
    expect(upgrade.status).toBe(101);
    const ws = upgrade.webSocket as WebSocket;
    const closed = new Promise<number>((resolve) => {
      ws.addEventListener("close", (event) => resolve(event.code));
    });
    ws.accept();

    await deleteAccount(owner.tokens.accessToken);

    expect(await closed).toBe(1012);
  });
});

describe("deleting an account from the web page (ADR 0007)", () => {
  const ask = (email: string, address?: string) =>
    SELF.fetch("https://dielys.test/account/deletion/request", {
      method: "POST",
      headers: { "content-type": "application/json", "CF-Connecting-IP": address ?? nextAddress() },
      body: JSON.stringify({ email }),
    });
  const confirm = (token: string) => post("/account/deletion/confirm", { token });

  it("mails a link back to the page it was asked from, and the button erases the account", async () => {
    const gone = await person();
    const listId = await ownedList(gone);

    const asked = await ask(gone.email.toUpperCase());
    expect(asked.status).toBe(200);
    expect(await asked.json()).toEqual({ expiresIn: 15 * 60 });

    expect(deletionMails).toHaveLength(1);
    const { to, link } = deletionMails[0] as { to: string; link: URL };
    expect(to).toBe(gone.email);
    expect(link.origin).toBe("https://dielys.test");
    expect(link.pathname).toBe("/account/delete/confirm");
    const token = link.searchParams.get("token") as string;

    // Asking alone changes nothing: the account still works.
    expect((await get("/auth/memberships", gone.tokens.accessToken)).status).toBe(200);
    const refreshStillWorks = await post("/auth/refresh", {
      refreshToken: gone.tokens.refreshToken,
      deviceId: gone.deviceId,
    });
    expect(refreshStillWorks.status).toBe(200);

    expect((await confirm(token)).status).toBe(204);

    const login = await post("/auth/login", {
      email: gone.email,
      password: PASSWORD,
      deviceId: gone.deviceId,
    });
    expect(login.status).toBe(401);
    const stranger = await person();
    expect((await post(`/lists/${listId}`, {}, stranger.tokens.accessToken)).status).toBe(200);
    expect(await changeCount(listId, stranger.tokens.accessToken)).toBe(0);
  });

  it("answers the same for an address with no account, and mails nothing", async () => {
    const asked = await ask(uniqueEmail());
    expect(asked.status).toBe(200);
    expect(await asked.json()).toEqual({ expiresIn: 15 * 60 });
    expect(deletionMails).toHaveLength(0);
  });

  it("a link works once", async () => {
    const gone = await person();
    await ask(gone.email);
    const token = deletionMails[0]?.link.searchParams.get("token") as string;

    expect((await confirm(token)).status).toBe(204);
    const replay = await confirm(token);
    expect(replay.status).toBe(401);
    expect(((await replay.json()) as { code: string }).code).toBe("invalid-token");
  });

  it("a newer request replaces the older link", async () => {
    const kept = await person();
    await ask(kept.email);
    await ask(kept.email);
    const [older, newer] = deletionMails.map((m) => m.link.searchParams.get("token") as string);

    expect((await confirm(older as string)).status).toBe(401);
    expect((await confirm(newer as string)).status).toBe(204);
  });

  it("never lets a deletion token sign anybody in", async () => {
    const kept = await person();
    await ask(kept.email);
    const token = deletionMails[0]?.link.searchParams.get("token") as string;

    const signIn = await post("/auth/magic/verify", { token, deviceId: "device-z" });
    expect(signIn.status).toBe(401);
    // And trying did not spend it.
    expect((await confirm(token)).status).toBe(204);
  });

  it("refuses a made-up token and a malformed body", async () => {
    expect((await confirm("not-a-real-token")).status).toBe(401);
    expect((await post("/account/deletion/confirm", { token: "" })).status).toBe(400);
    expect((await ask("not-an-email")).status).toBe(400);
  });

  it("stops one address being mailed over and over", async () => {
    const target = await person();
    const statuses: number[] = [];
    for (let i = 0; i < 4; i++) statuses.push((await ask(target.email)).status);
    expect(statuses).toEqual([200, 200, 200, 429]);
    expect(deletionMails).toHaveLength(3);
  });
});
