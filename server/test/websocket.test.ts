import { env } from "cloudflare:test";
import type { ServerMessage } from "@dielys/protocol";
import { describe, expect, it } from "vitest";

/**
 * The socket is a latency optimization, not the correctness path (F5.6) — but
 * it still has to say the right thing, especially to a client it is turning
 * away (H3.12).
 */

function room(): { stub: DurableObjectStub; listId: string } {
  const listId = `ws-${crypto.randomUUID()}`;
  return { stub: env.LIST_ROOM.get(env.LIST_ROOM.idFromName(listId)), listId };
}

/** A socket plus a queue, so a test can await the next message it expects. */
async function connect(stub: DurableObjectStub, listId: string, deviceId = "device-a") {
  const response = await stub.fetch(`https://list-room/ws?listId=${listId}&deviceId=${deviceId}`, {
    headers: { Upgrade: "websocket" },
  });
  expect(response.status).toBe(101);
  const ws = response.webSocket;
  if (!ws) throw new Error("no websocket on upgrade response");
  ws.accept();

  const received: ServerMessage[] = [];
  const waiters: Array<(message: ServerMessage) => void> = [];
  ws.addEventListener("message", (event) => {
    const message = JSON.parse(String(event.data)) as ServerMessage;
    const waiter = waiters.shift();
    if (waiter) waiter(message);
    else received.push(message);
  });

  const closed = new Promise<{ code: number }>((resolve) => {
    ws.addEventListener("close", (event) => resolve({ code: event.code }));
  });

  return {
    ws,
    closed,
    send(message: unknown) {
      ws.send(JSON.stringify(message));
    },
    next(): Promise<ServerMessage> {
      const buffered = received.shift();
      if (buffered) return Promise.resolve(buffered);
      return new Promise((resolve) => waiters.push(resolve));
    },
  };
}

const hello = (listId: string, over: Record<string, unknown> = {}) => ({
  type: "hello",
  protocolVersion: 2,
  listId,
  cursor: 0,
  deviceId: "device-a",
  ...over,
});

describe("ListRoom WebSocket — handshake", () => {
  it("accepts a current client and reports where the log has got to", async () => {
    const { stub, listId } = room();
    const client = await connect(stub, listId);
    client.send(hello(listId));

    const message = await client.next();
    expect(message.type).toBe("hello-ok");
    if (message.type !== "hello-ok") return;
    expect(message.protocolVersion).toBe(2);
    // maxSeq up front is what lets a client notice it is behind without
    // waiting for a push that may never come.
    expect(message.maxSeq).toBe(0);
  });

  it("H3.12: an old client gets a named reason, not a dropped connection", async () => {
    const { stub, listId } = room();
    const client = await connect(stub, listId);
    client.send(hello(listId, { protocolVersion: 1 }));

    const message = await client.next();
    expect(message.type).toBe("hello-error");
    if (message.type !== "hello-error") return;
    expect(message.code).toBe("unsupported-protocol-version");
    // Told first, closed after — a client that only saw the close could not
    // tell "upgrade me" from "the network died".
    expect((await client.closed).code).toBe(1008);
  });

  it("rejects a malformed hello with a code rather than a silent drop", async () => {
    const { stub, listId } = room();
    const client = await connect(stub, listId);
    client.send({ type: "hello", protocolVersion: 2 });

    const message = await client.next();
    expect(message.type).toBe("hello-error");
    if (message.type !== "hello-error") return;
    expect(message.code).toBe("malformed");
  });
});

describe("ListRoom WebSocket — mutations", () => {
  it("acks the sender and pushes the change to everyone else", async () => {
    const { stub, listId } = room();
    const author = await connect(stub, listId, "device-a");
    const peer = await connect(stub, listId, "device-b");
    author.send(hello(listId));
    peer.send(hello(listId, { deviceId: "device-b" }));
    await author.next();
    await peer.next();

    author.send({
      type: "mutate",
      protocolVersion: 2,
      listId,
      entityType: "task",
      entityId: crypto.randomUUID(),
      idempotencyKey: crypto.randomUUID(),
      deviceId: "device-a",
      patch: { title: "Milk", position: "a0" },
    });

    const ack = await author.next();
    expect(ack.type).toBe("ack");
    if (ack.type !== "ack") return;
    expect(ack.change.seq).toBe(1);

    const pushed = await peer.next();
    expect(pushed.type).toBe("change");
    if (pushed.type !== "change") return;
    // The peer sees the change once, and does not also receive an ack that
    // was not addressed to it.
    expect(pushed.change.seq).toBe(1);
    expect(pushed.change.entity.title).toBe("Milk");
  });

  it("F3: an invalid mutation is answered with an error and the socket stays open", async () => {
    const { stub, listId } = room();
    const client = await connect(stub, listId);
    client.send(hello(listId));
    await client.next();

    client.send({ type: "mutate", protocolVersion: 2, listId });
    const error = await client.next();
    expect(error.type).toBe("error");
    if (error.type !== "error") return;
    expect(error.code).toBe("malformed");

    // Still usable: a validation failure must not cost the session.
    client.send({ type: "catch-up", listId, since: 0 });
    const response = await client.next();
    expect(response.type).toBe("catch-up-response");
  });

  it("answers an unknown message type without dropping the socket", async () => {
    const { stub, listId } = room();
    const client = await connect(stub, listId);
    client.send({ type: "something-from-the-future" });

    const message = await client.next();
    expect(message.type).toBe("error");
  });
});

describe("ListRoom WebSocket — catch-up over the socket (F5.6)", () => {
  it("H3.11: the same changes arrive over the socket as over HTTP", async () => {
    const { stub, listId } = room();
    await stub.fetch(`https://list-room/mutate?listId=${listId}`, {
      method: "POST",
      body: JSON.stringify({
        type: "mutate",
        protocolVersion: 2,
        listId,
        entityType: "task",
        entityId: crypto.randomUUID(),
        idempotencyKey: crypto.randomUUID(),
        deviceId: "device-a",
        patch: { title: "Milk", position: "a0" },
      }),
    });

    const client = await connect(stub, listId);
    client.send(hello(listId));
    await client.next();
    client.send({ type: "catch-up", listId, since: 0 });

    const overSocket = await client.next();
    expect(overSocket.type).toBe("catch-up-response");
    if (overSocket.type !== "catch-up-response") return;

    const overHttp = await (
      await stub.fetch(`https://list-room/changes?listId=${listId}&since=0`)
    ).json();

    // Both delivery paths apply through identical code — the socket dying is
    // a slower catch-up, never a missed change.
    expect(overSocket).toEqual(overHttp);
  });
});
