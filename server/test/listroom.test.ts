import { env, runInDurableObject } from "cloudflare:test";
import {
  CATCH_UP_PAGE_SIZE,
  type CatchUpResponse,
  type Mutation,
  type MutationAck,
  type ServerError,
} from "@dielys/protocol";
import { describe, expect, it } from "vitest";
import { currentVersion } from "../src/storage/migrations.js";

/**
 * ListRoom integration tests. These run in real workerd against real DO
 * storage (H1) — the single-threading and transaction guarantees the whole
 * design leans on cannot be reproduced by a mock.
 *
 * Scenario coverage from H3: 3.2, 3.3, 3.5, 3.6, 3.7, 3.8, 3.10, 3.11, 3.12.
 * The rest (3.1, 3.4, 3.9) are client-side and belong to the Android suite.
 */

let listCounter = 0;

/** A fresh DO per test — DO storage persists for the life of a named object. */
function room(): { stub: DurableObjectStub; listId: string } {
  listCounter += 1;
  const listId = `list-${listCounter}-${crypto.randomUUID()}`;
  return { stub: env.LIST_ROOM.get(env.LIST_ROOM.idFromName(listId)), listId };
}

function taskMutation(listId: string, over: Partial<Mutation> & Record<string, unknown> = {}) {
  return {
    type: "mutate",
    protocolVersion: 2,
    listId,
    entityType: "task",
    entityId: crypto.randomUUID(),
    idempotencyKey: crypto.randomUUID(),
    deviceId: "device-a",
    patch: { title: "Milk", position: "a0" },
    ...over,
  };
}

async function mutate(
  stub: DurableObjectStub,
  listId: string,
  body: unknown,
): Promise<{ status: number; json: MutationAck | ServerError }> {
  const response = await stub.fetch(`https://list-room/mutate?listId=${listId}`, {
    method: "POST",
    body: JSON.stringify(body),
  });
  return { status: response.status, json: (await response.json()) as MutationAck | ServerError };
}

async function changesSince(
  stub: DurableObjectStub,
  listId: string,
  since: number,
): Promise<CatchUpResponse> {
  const response = await stub.fetch(`https://list-room/changes?listId=${listId}&since=${since}`);
  expect(response.status).toBe(200);
  return (await response.json()) as CatchUpResponse;
}

describe("ListRoom — migrations (G1)", () => {
  it("brings a fresh DO to the latest schema version", async () => {
    const { stub, listId } = room();
    await changesSince(stub, listId, 0);
    await runInDurableObject(stub, (_instance, state) => {
      expect(currentVersion(state.storage.sql)).toBe(2);
    });
  });

  it("does not re-run a migration on a second access", async () => {
    const { stub, listId } = room();
    await changesSince(stub, listId, 0);
    await changesSince(stub, listId, 0);
    await runInDurableObject(stub, (_instance, state) => {
      const rows = [...state.storage.sql.exec("SELECT version FROM _migrations ORDER BY version")];
      expect(rows.map((r) => Number(r.version))).toEqual([1, 2]);
    });
  });
});

describe("ListRoom — sequence numbers (D3, F5.6)", () => {
  it("assigns seqs from 1, contiguously, in arrival order", async () => {
    const { stub, listId } = room();
    const seqs: number[] = [];
    for (let i = 0; i < 5; i += 1) {
      const { json } = await mutate(stub, listId, taskMutation(listId));
      expect(json.type).toBe("ack");
      if (json.type !== "ack") return;
      seqs.push(json.change.seq);
    }
    expect(seqs).toEqual([1, 2, 3, 4, 5]);
  });

  it("never reuses a seq after a rejected mutation", async () => {
    const { stub, listId } = room();
    await mutate(stub, listId, taskMutation(listId));

    // Rejected: a create with no title cannot be completed by the server.
    const rejected = await mutate(
      stub,
      listId,
      taskMutation(listId, { patch: { position: "a1" } }),
    );
    expect(rejected.status).toBe(422);
    expect(rejected.json.type).toBe("error");

    const accepted = await mutate(stub, listId, taskMutation(listId));
    expect(accepted.json.type).toBe("ack");
    if (accepted.json.type !== "ack") return;
    // The rejected attempt burned no seq, so the changelog stays contiguous —
    // a gap here would make every client trigger a pointless catch-up.
    expect(accepted.json.change.seq).toBe(2);
  });
});

describe("ListRoom — idempotency (F5.2)", () => {
  it("H3.2 / H3.10: a redelivered key returns the original result, not a second change", async () => {
    const { stub, listId } = room();
    const mutation = taskMutation(listId);

    const first = await mutate(stub, listId, mutation);
    expect(first.json.type).toBe("ack");
    if (first.json.type !== "ack") return;
    expect(first.json.duplicate).toBe(false);

    // The response was lost; the outbox retries with the same key.
    const second = await mutate(stub, listId, mutation);
    expect(second.json.type).toBe("ack");
    if (second.json.type !== "ack") return;
    expect(second.json.duplicate).toBe(true);
    expect(second.json.change).toEqual(first.json.change);

    const changes = await changesSince(stub, listId, 0);
    expect(changes.changes).toHaveLength(1);
  });

  it("a retry whose patch differs still returns the original result", async () => {
    const { stub, listId } = room();
    const mutation = taskMutation(listId);
    const first = await mutate(stub, listId, mutation);
    if (first.json.type !== "ack") return;

    // The key is the identity of the mutation. A client that reuses one with
    // different content is buggy, and the server must not apply it twice
    // under any reading of "the same tap".
    const second = await mutate(stub, listId, {
      ...mutation,
      patch: { title: "Something else", position: "z9" },
    });
    expect(second.json.type).toBe("ack");
    if (second.json.type !== "ack") return;
    expect(second.json.change).toEqual(first.json.change);
  });
});

describe("ListRoom — conflicts through real storage (F5.4)", () => {
  it("H3.5: both devices tick the same task, converging with no error", async () => {
    const { stub, listId } = room();
    const created = await mutate(stub, listId, taskMutation(listId));
    if (created.json.type !== "ack") return;
    const entityId = created.json.change.entity.id;

    for (const deviceId of ["device-a", "device-b"]) {
      const result = await mutate(
        stub,
        listId,
        taskMutation(listId, { entityId, deviceId, patch: { done: true } }),
      );
      expect(result.json.type).toBe("ack");
    }

    const changes = await changesSince(stub, listId, 0);
    const last = changes.changes.at(-1);
    expect(last?.entityType).toBe("task");
    if (last?.entityType !== "task") return;
    expect(last.entity.done).toBe(true);
  });

  it("a rename and a tick from two devices both survive", async () => {
    const { stub, listId } = room();
    const created = await mutate(stub, listId, taskMutation(listId));
    if (created.json.type !== "ack") return;
    const entityId = created.json.change.entity.id;

    await mutate(
      stub,
      listId,
      taskMutation(listId, { entityId, deviceId: "device-a", patch: { title: "Melk" } }),
    );
    const ticked = await mutate(
      stub,
      listId,
      taskMutation(listId, { entityId, deviceId: "device-b", patch: { done: true } }),
    );

    expect(ticked.json.type).toBe("ack");
    if (ticked.json.type !== "ack" || ticked.json.change.entityType !== "task") return;
    expect(ticked.json.change.entity.title).toBe("Melk");
    expect(ticked.json.change.entity.done).toBe(true);
  });

  it("H3.7: a delete racing an update leaves the row tombstoned, not resurrected", async () => {
    const { stub, listId } = room();
    const created = await mutate(stub, listId, taskMutation(listId));
    if (created.json.type !== "ack") return;
    const entityId = created.json.change.entity.id;

    await mutate(
      stub,
      listId,
      taskMutation(listId, {
        entityId,
        deviceId: "device-a",
        patch: { deletedAt: "2026-09-08T10:00:00.000Z" },
      }),
    );
    const update = await mutate(
      stub,
      listId,
      taskMutation(listId, {
        entityId,
        deviceId: "device-b",
        patch: { deletedAt: null, title: "resurrected" },
      }),
    );

    expect(update.json.type).toBe("ack");
    if (update.json.type !== "ack" || update.json.change.entityType !== "task") return;
    expect(update.json.change.entity.deletedAt).toBe("2026-09-08T10:00:00.000Z");

    // F5.3: the row is still there, tombstoned — never DELETEd.
    await runInDurableObject(stub, (_instance, state) => {
      const rows = [
        ...state.storage.sql.exec("SELECT deleted_at FROM tasks WHERE id = ?", entityId),
      ];
      expect(rows).toHaveLength(1);
      expect(rows[0]?.deleted_at).toBe("2026-09-08T10:00:00.000Z");
    });
  });

  it("H3.6: two devices creating tasks offline both survive with distinct ids", async () => {
    const { stub, listId } = room();
    const a = await mutate(
      stub,
      listId,
      taskMutation(listId, { deviceId: "device-a", patch: { title: "Milk", position: "a0" } }),
    );
    const b = await mutate(
      stub,
      listId,
      taskMutation(listId, { deviceId: "device-b", patch: { title: "Bread", position: "a1" } }),
    );

    if (a.json.type !== "ack" || b.json.type !== "ack") return;
    expect(a.json.change.entity.id).not.toBe(b.json.change.entity.id);

    const changes = await changesSince(stub, listId, 0);
    expect(changes.changes).toHaveLength(2);
    expect(changes.changes.map((c) => c.entity.title).sort()).toEqual(["Bread", "Milk"]);
  });
});

describe("ListRoom — catch-up (F5.6)", () => {
  it("H3.3: ?since=N returns exactly the tail after the cursor", async () => {
    const { stub, listId } = room();
    for (let i = 0; i < 4; i += 1) await mutate(stub, listId, taskMutation(listId));

    const tail = await changesSince(stub, listId, 2);
    expect(tail.changes.map((c) => c.seq)).toEqual([3, 4]);
    expect(tail.maxSeq).toBe(4);
    expect(tail.since).toBe(2);
    expect(tail.truncated).toBe(false);
  });

  it("an empty list returns an empty page rather than an error", async () => {
    const { stub, listId } = room();
    const empty = await changesSince(stub, listId, 0);
    expect(empty.changes).toEqual([]);
    expect(empty.maxSeq).toBe(0);
    expect(empty.truncated).toBe(false);
  });

  it("a cursor past the end returns nothing and still reports maxSeq", async () => {
    const { stub, listId } = room();
    await mutate(stub, listId, taskMutation(listId));
    const ahead = await changesSince(stub, listId, 99);
    expect(ahead.changes).toEqual([]);
    // The client needs maxSeq to notice its cursor is impossible and reset.
    expect(ahead.maxSeq).toBe(1);
  });

  it("pages, and says so, when more changes remain than fit", async () => {
    const { stub, listId } = room();
    await changesSince(stub, listId, 0); // force migration before writing rows

    // Seeded directly: the assertion is about paging, and 501 HTTP round
    // trips would make this test slow for no extra coverage.
    await runInDurableObject(stub, (_instance, state) => {
      for (let seq = 1; seq <= CATCH_UP_PAGE_SIZE + 1; seq += 1) {
        state.storage.sql.exec(
          `INSERT INTO changes (seq, idempotency_key, device_id, server_timestamp, entity_type, entity_json)
           VALUES (?, ?, ?, ?, ?, ?)`,
          seq,
          `key-${seq}`,
          "device-a",
          "2026-09-08T10:00:00.000Z",
          "task",
          JSON.stringify({
            id: `task-${seq}`,
            listId,
            title: "Milk",
            done: false,
            starred: false,
            position: "a0",
            deletedAt: null,
            updatedAt: "2026-09-08T10:00:00.000Z",
          }),
        );
      }
    });

    const first = await changesSince(stub, listId, 0);
    expect(first.changes).toHaveLength(CATCH_UP_PAGE_SIZE);
    expect(first.truncated).toBe(true);
    expect(first.maxSeq).toBe(CATCH_UP_PAGE_SIZE + 1);

    const second = await changesSince(stub, listId, CATCH_UP_PAGE_SIZE);
    expect(second.changes).toHaveLength(1);
    expect(second.truncated).toBe(false);
  });

  it("rejects a malformed since rather than treating it as 0", async () => {
    const { stub, listId } = room();
    const response = await stub.fetch(`https://list-room/changes?listId=${listId}&since=abc`);
    expect(response.status).toBe(400);
  });
});

describe("ListRoom — list identity", () => {
  it("refuses a request for a different list than it was first bound to", async () => {
    const { stub, listId } = room();
    await changesSince(stub, listId, 0);

    // A Worker routing bug must surface as an error, not as a silent write
    // into someone else's list.
    const response = await stub.fetch("https://list-room/changes?listId=someone-elses-list");
    expect(response.status).toBe(409);
    const body = (await response.json()) as ServerError;
    expect(body.code).toBe("list-mismatch");
  });

  it("requires a listId at all", async () => {
    const { stub } = room();
    const response = await stub.fetch("https://list-room/changes");
    expect(response.status).toBe(400);
  });
});

describe("ListRoom — protocol version (F2, H3.12)", () => {
  it("rejects an unsupported version with a code the client can act on", async () => {
    const { stub, listId } = room();
    const { status, json } = await mutate(
      stub,
      listId,
      taskMutation(listId, { protocolVersion: 1 }),
    );
    expect(status).toBe(400);
    expect(json.type).toBe("error");
    if (json.type !== "error") return;
    expect(json.code).toBe("unsupported-protocol-version");
  });
});

describe("ListRoom — validation at the boundary (F3)", () => {
  it("rejects non-JSON without crashing the DO", async () => {
    const { stub, listId } = room();
    const response = await stub.fetch(`https://list-room/mutate?listId=${listId}`, {
      method: "POST",
      body: "{not json",
    });
    expect(response.status).toBe(400);

    // Still alive and still serving.
    const changes = await changesSince(stub, listId, 0);
    expect(changes.changes).toEqual([]);
  });

  it("rejects a title over the bound and writes nothing", async () => {
    const { stub, listId } = room();
    const { status } = await mutate(
      stub,
      listId,
      taskMutation(listId, { patch: { title: "x".repeat(1001), position: "a0" } }),
    );
    expect(status).toBe(400);
    const changes = await changesSince(stub, listId, 0);
    expect(changes.changes).toEqual([]);
  });

  it("rejects GET on the mutate route", async () => {
    const { stub, listId } = room();
    const response = await stub.fetch(`https://list-room/mutate?listId=${listId}`);
    expect(response.status).toBe(405);
  });

  it("404s an unknown path", async () => {
    const { stub, listId } = room();
    const response = await stub.fetch(`https://list-room/nope?listId=${listId}`);
    expect(response.status).toBe(404);
  });
});
