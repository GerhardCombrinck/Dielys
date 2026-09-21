/**
 * The offline scenarios (H3) this client can reach, against a fake server
 * that keeps a real changelog and can be taken offline or made to refuse.
 */
import type {
  CatchUpResponse,
  ChangeEnvelope,
  Membership,
  Mutation,
  MutationAck,
  SetListPositionRequest,
  Task,
  TaskList,
} from "@dielys/protocol";
import { beforeEach, describe, expect, it } from "vitest";
import { ApiError } from "../api/client.js";
import { Replica } from "./replica.js";
import { Repository } from "./repository.js";
import { classify, type SyncApi, SyncEngine } from "./syncEngine.js";

class FakeServer implements SyncApi {
  offline = false;
  refuse: ApiError | null = null;
  pageSize = 500;
  readonly changelogs = new Map<string, ChangeEnvelope[]>();
  readonly members = new Map<string, Membership>();
  readonly sentKeys: string[] = [];
  private readonly seen = new Map<string, MutationAck>();
  private clock = 0;

  private check(): void {
    if (this.offline) throw new TypeError("Failed to fetch");
    if (this.refuse !== null) throw this.refuse;
  }

  async memberships(): Promise<Membership[]> {
    this.check();
    return [...this.members.values()].map((m) => ({
      ...m,
      maxSeq: this.changelogs.get(m.listId)?.length ?? 0,
    }));
  }

  async changes(listId: string, since: number): Promise<CatchUpResponse> {
    this.check();
    const log = this.changelogs.get(listId) ?? [];
    const after = log.filter((c) => c.seq > since);
    const page = after.slice(0, this.pageSize);
    return {
      type: "catch-up-response",
      listId,
      since,
      changes: page,
      maxSeq: log.length,
      truncated: after.length > page.length,
    };
  }

  async claim(listId: string): Promise<unknown> {
    this.check();
    this.members.set(listId, {
      listId,
      role: "owner",
      position: null,
      memberCount: 1,
      maxSeq: null,
    });
    this.changelogs.set(listId, this.changelogs.get(listId) ?? []);
    return { listId, alreadyMember: false };
  }

  async setListPosition(request: SetListPositionRequest): Promise<unknown> {
    this.check();
    const m = this.members.get(request.listId);
    if (m !== undefined) this.members.set(request.listId, { ...m, position: request.position });
    return {};
  }

  async mutate(listId: string, mutation: Mutation): Promise<MutationAck> {
    this.check();
    this.sentKeys.push(mutation.idempotencyKey);
    const previous = this.seen.get(mutation.idempotencyKey);
    if (previous !== undefined) return { ...previous, duplicate: true };
    if (!this.members.has(listId)) throw new ApiError(403, "forbidden");
    const change = this.record(listId, mutation);
    const ack: MutationAck = {
      type: "ack",
      idempotencyKey: mutation.idempotencyKey,
      change,
      duplicate: false,
    };
    this.seen.set(mutation.idempotencyKey, ack);
    return ack;
  }

  /** Another device's edit, straight into the changelog. */
  record(listId: string, mutation: Mutation): ChangeEnvelope {
    const log = this.changelogs.get(listId) ?? [];
    this.changelogs.set(listId, log);
    const updatedAt = new Date(Date.UTC(2026, 8, 21, 0, 0, this.clock++)).toISOString();
    const base = {
      seq: log.length + 1,
      listId,
      idempotencyKey: mutation.idempotencyKey,
      deviceId: mutation.deviceId,
      serverTimestamp: updatedAt,
    };
    const existing = [...log].reverse().find((c) => c.entity.id === mutation.entityId)?.entity;
    let change: ChangeEnvelope;
    if (mutation.entityType === "task") {
      const entity: Task = {
        id: mutation.entityId,
        listId,
        title: "",
        done: false,
        starred: false,
        position: "a0",
        deletedAt: null,
        ...(existing as Task | undefined),
        ...mutation.patch,
        updatedAt,
      } as Task;
      change = { ...base, entityType: "task", entity };
    } else {
      const entity: TaskList = {
        id: mutation.entityId,
        title: "",
        backgroundPhotoUrl: null,
        deletedAt: null,
        ...(existing as TaskList | undefined),
        ...mutation.patch,
        updatedAt,
      } as TaskList;
      change = { ...base, entityType: "list", entity };
    }
    log.push(change);
    return change;
  }
}

let server: FakeServer;
let replica: Replica;
let engine: SyncEngine;
let repo: Repository;

beforeEach(() => {
  server = new FakeServer();
  replica = new Replica();
  engine = new SyncEngine(replica, server);
  repo = new Repository(replica, "this-device", () => {});
});

async function createSyncedList(title = "Shop"): Promise<string> {
  const id = repo.createList(title);
  expect(await engine.sync()).toBe("success");
  return id;
}

describe("local-first writes", () => {
  it("shows an edit before the server has heard of it", () => {
    const listId = repo.createList("Shop");
    const taskId = repo.addTask(listId, "Milk", false);
    expect(replica.getList(listId)?.list?.title).toBe("Shop");
    expect(replica.getTask(taskId)?.title).toBe("Milk");
    expect(replica.pendingCount()).toBe(3); // claim, name, task
  });

  it("claims a list before naming it, and names it before filling it", async () => {
    const listId = repo.createList("Shop");
    repo.addTask(listId, "Milk", false);
    expect(await engine.drain()).toBe("success");
    const log = server.changelogs.get(listId) ?? [];
    expect(log.map((c) => c.entityType)).toEqual(["list", "task"]);
    expect(replica.cursor(listId)).toBe(2);
    expect(replica.pendingCount()).toBe(0);
  });
});

describe("offline (H3)", () => {
  it("keeps edits made offline and sends them once back online (H3.1)", async () => {
    const listId = await createSyncedList();
    server.offline = true;
    const taskId = repo.addTask(listId, "Bread", false);
    repo.setStarred(taskId, true);

    expect(await engine.sync()).toBe("retry");
    expect(replica.getTask(taskId)?.starred).toBe(true);
    expect(replica.pendingCount()).toBe(2);

    server.offline = false;
    expect(await engine.sync()).toBe("success");
    expect(replica.pendingCount()).toBe(0);
    const onServer = server.changelogs.get(listId)?.at(-1)?.entity as Task;
    expect(onServer).toMatchObject({ id: taskId, title: "Bread", starred: true });
  });

  it("resends a lost ack with the same idempotency key (F5.2, H3.4)", async () => {
    const listId = await createSyncedList();
    repo.addTask(listId, "Eggs", false);
    const key = replica.nextPending()?.key as string;

    // The server applies it, but the answer never arrives.
    const mutate = server.mutate.bind(server);
    server.mutate = async (id, m) => {
      await mutate(id, m);
      throw new TypeError("connection reset");
    };
    expect(await engine.drain()).toBe("retry");
    expect(replica.nextPending()?.key).toBe(key);

    server.mutate = mutate;
    expect(await engine.drain()).toBe("success");
    expect(server.sentKeys.filter((k) => k === key)).toHaveLength(2);
    expect(server.changelogs.get(listId)?.filter((c) => c.entityType === "task")).toHaveLength(1);
  });

  it("parks a refused edit and carries on with the rest", async () => {
    const listId = await createSyncedList();
    const refused = repo.addTask(listId, "Keep", false);
    server.members.delete(listId); // removed from the list while this was queued
    const other = repo.createList("Other");
    const fine = repo.addTask(other, "Also keep", false);

    expect(await engine.drain()).toBe("success");
    expect(replica.stuckCount()).toBe(1);
    expect(replica.pendingCount()).toBe(0);
    // Parked, not dropped: what was typed stays on screen.
    expect(replica.getTask(refused)?.title).toBe("Keep");
    expect(server.changelogs.get(other)?.at(-1)?.entity.id).toBe(fine);
  });

  it("stops without dropping anything when the session is over", async () => {
    const listId = await createSyncedList();
    repo.addTask(listId, "Tea", false);
    server.refuse = new ApiError(401, "unauthorized");
    expect(await engine.drain()).toBe("session-expired");
    expect(replica.pendingCount()).toBe(1);
  });
});

describe("pulling", () => {
  it("finds a list shared with this account and pulls it by pages", async () => {
    server.members.set("shared", {
      listId: "shared",
      role: "member",
      position: null,
      memberCount: 2,
      maxSeq: null,
    });
    const other = (entityId: string, patch: object, type: "task" | "list" = "task") =>
      server.record("shared", {
        type: "mutate",
        protocolVersion: 1,
        listId: "shared",
        idempotencyKey: `other-${entityId}-${Math.random()}`,
        deviceId: "other",
        entityId,
        entityType: type,
        patch,
      } as Mutation);
    other("shared", { title: "Theirs" }, "list");
    for (let i = 0; i < 5; i++) other(`t${i}`, { title: `item ${i}`, position: `a${i}` });
    server.pageSize = 2;

    expect(await engine.sync()).toBe("success");
    expect(replica.getList("shared")?.list?.title).toBe("Theirs");
    expect(replica.getList("shared")?.role).toBe("member");
    expect(replica.tasksIn("shared")).toHaveLength(5);
    expect(replica.cursor("shared")).toBe(6);
  });

  it("fills a gap an ack reveals instead of skipping it (F5.6)", async () => {
    const listId = await createSyncedList();
    // Someone else edits while this browser is not listening...
    server.record(listId, {
      type: "mutate",
      protocolVersion: 1,
      listId,
      idempotencyKey: "theirs",
      deviceId: "other",
      entityId: "their-task",
      entityType: "task",
      patch: { title: "Theirs", position: "a0" },
    } as Mutation);
    // ...so this browser's next ack lands one past a seq it never saw.
    repo.addTask(listId, "Mine", false);
    expect(await engine.drain()).toBe("success");
    expect(replica.getTask("their-task")?.title).toBe("Theirs");
    expect(replica.cursor(listId)).toBe(3);
  });

  it("forgets a list this account was removed from (#60)", async () => {
    const listId = await createSyncedList();
    server.members.delete(listId);
    expect(await engine.sync()).toBe("success");
    expect(replica.getList(listId)).toBeNull();
  });
});

describe("classify", () => {
  it("retries what can succeed later and parks what cannot", () => {
    expect(classify(new TypeError("Failed to fetch"))).toBe("retry");
    expect(classify(new ApiError(503, "internal"))).toBe("retry");
    expect(classify(new ApiError(429, "rate-limited"))).toBe("retry");
    expect(classify(new ApiError(401, "unauthorized"))).toBe("session-expired");
    expect(classify(new ApiError(403, "forbidden"))).toBe("rejected");
    expect(classify(new ApiError(400, "malformed"))).toBe("rejected");
  });
});
