import { env, SELF } from "cloudflare:test";
import type { TokenPair } from "@dielys/protocol";
import { describe, expect, it } from "vitest";

/**
 * Device registration and the wake fan-out (M2), through the real Worker and
 * the real UsersRoom (H1).
 *
 * The send itself is covered in fcm.test.ts. What matters here is who ends up
 * in the fan-out set and who does not, because that is where a mistake sends
 * somebody else's phone a push about a list they cannot see.
 */

const ADMIN = "test-admin-token-not-used-anywhere-real";
const PASSWORD = "a-generated-password-long-enough";

let seq = 0;
function uniqueEmail(): string {
  seq += 1;
  return `device-${seq}-${crypto.randomUUID()}@dielys.test`;
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

async function signedIn(deviceId: string): Promise<TokenPair> {
  const email = uniqueEmail();
  const created = await post("/admin/users", { email, password: PASSWORD }, ADMIN);
  expect(created.status).toBe(201);
  const response = await post("/auth/login", { email, password: PASSWORD, deviceId });
  expect(response.status).toBe(200);
  return (await response.json()) as TokenPair;
}

function users() {
  return env.USERS_ROOM.get(env.USERS_ROOM.idFromName("users-v1"));
}

describe("registering a device token (M2)", () => {
  it("stores the token against the caller's device", async () => {
    const session = await signedIn("phone-a");
    const listId = crypto.randomUUID();
    expect((await post(`/lists/${listId}`, {}, session.accessToken)).status).toBe(200);

    const registered = await post(
      "/devices/token",
      { fcmToken: "fcm-token-a" },
      session.accessToken,
    );
    expect(registered.status).toBe(204);

    const devices = await users().devicesForList(listId);
    expect(devices).toEqual([
      { deviceId: "phone-a", userId: session.userId, fcmToken: "fcm-token-a" },
    ]);
  });

  it("refuses without a session", async () => {
    expect((await post("/devices/token", { fcmToken: "fcm-token-a" })).status).toBe(401);
  });

  it("refuses a body that is not a token", async () => {
    const session = await signedIn("phone-a");
    expect((await post("/devices/token", {}, session.accessToken)).status).toBe(400);
    expect((await post("/devices/token", { fcmToken: "" }, session.accessToken)).status).toBe(400);
    expect(
      (await post("/devices/token", { fcmToken: "x".repeat(4097) }, session.accessToken)).status,
    ).toBe(400);
  });

  it("ignores a device id in the body and uses the one in the token", async () => {
    // A client that could name its own device id could register a push token
    // against somebody else's phone. The claim is the only source.
    const session = await signedIn("phone-a");
    const listId = crypto.randomUUID();
    await post(`/lists/${listId}`, {}, session.accessToken);

    const registered = await post(
      "/devices/token",
      { fcmToken: "fcm-token-a", deviceId: "somebody-elses-phone" },
      session.accessToken,
    );
    expect(registered.status).toBe(204);

    const devices = await users().devicesForList(listId);
    expect(devices.map((device) => device.deviceId)).toEqual(["phone-a"]);
  });

  it("replaces the token on refresh rather than adding a second row", async () => {
    const session = await signedIn("phone-a");
    const listId = crypto.randomUUID();
    await post(`/lists/${listId}`, {}, session.accessToken);

    await post("/devices/token", { fcmToken: "first" }, session.accessToken);
    // What onNewToken sends. A device with two rows would be woken twice, and
    // one of the two tokens would be dead.
    await post("/devices/token", { fcmToken: "second" }, session.accessToken);

    const devices = await users().devicesForList(listId);
    expect(devices).toHaveLength(1);
    expect(devices[0]?.fcmToken).toBe("second");
  });

  it("re-points a handed-on phone at whoever signs in on it", async () => {
    const first = await signedIn("shared-phone");
    const listId = crypto.randomUUID();
    await post(`/lists/${listId}`, {}, first.accessToken);
    await post("/devices/token", { fcmToken: "fcm-token" }, first.accessToken);

    // Same device id, different account. The row moves; it does not multiply.
    const second = await signedIn("shared-phone");
    const otherList = crypto.randomUUID();
    await post(`/lists/${otherList}`, {}, second.accessToken);
    await post("/devices/token", { fcmToken: "fcm-token" }, second.accessToken);

    expect(await users().devicesForList(listId)).toEqual([]);
    expect((await users().devicesForList(otherList))[0]?.userId).toBe(second.userId);
  });
});

describe("who a change wakes", () => {
  it("covers every member of the list and nobody else", async () => {
    const owner = await signedIn("phone-a");
    const listId = crypto.randomUUID();
    await post(`/lists/${listId}`, {}, owner.accessToken);
    await post("/devices/token", { fcmToken: "token-a" }, owner.accessToken);

    const invite = (await post(`/lists/${listId}/invite`, {}, owner.accessToken).then((r) =>
      r.json(),
    )) as { inviteToken: string };
    const guest = await signedIn("phone-b");
    expect(
      (await post("/invites/accept", { inviteToken: invite.inviteToken }, guest.accessToken))
        .status,
    ).toBe(200);
    await post("/devices/token", { fcmToken: "token-b" }, guest.accessToken);

    // A registered device on no shared list. Membership is the whole of the
    // fan-out set, so a stranger's phone must not appear here.
    const stranger = await signedIn("phone-c");
    await post("/devices/token", { fcmToken: "token-c" }, stranger.accessToken);

    const devices = await users().devicesForList(listId);
    expect(devices.map((device) => device.deviceId).sort()).toEqual(["phone-a", "phone-b"]);
  });

  it("leaves a list with no registered devices with nothing to send", async () => {
    const owner = await signedIn("phone-a");
    const listId = crypto.randomUUID();
    await post(`/lists/${listId}`, {}, owner.accessToken);

    expect(await users().devicesForList(listId)).toEqual([]);
  });
});

describe("a write with push unconfigured", () => {
  it("still commits and still acks", async () => {
    // FCM_SERVICE_ACCOUNT_JSON is "{}" here, as it is on any deployment that
    // has never had the secret set. A push is best-effort (H3.12), so an
    // absent credential must cost latency and nothing else — unlike
    // JWT_SIGNING_KEY, where absent means the Worker serves nothing.
    const owner = await signedIn("phone-a");
    const listId = crypto.randomUUID();
    await post(`/lists/${listId}`, {}, owner.accessToken);
    await post("/devices/token", { fcmToken: "token-a" }, owner.accessToken);

    const response = await post(
      `/lists/${listId}/mutate`,
      {
        type: "mutate",
        protocolVersion: 2,
        listId,
        idempotencyKey: crypto.randomUUID(),
        deviceId: "phone-a",
        entityType: "list",
        entityId: listId,
        patch: { title: "Groceries" },
      },
      owner.accessToken,
    );

    expect(response.status).toBe(200);
    expect(await response.json()).toMatchObject({ type: "ack", duplicate: false });
  });
});
