import type { ChangeEnvelope, Membership, Task, TaskList } from "@dielys/protocol";
import { describe, expect, it } from "vitest";
import { type LocalList, Replica, type WriteBatch } from "./replica.js";

const LIST = "list-1";

function task(id: string, patch: Partial<Task> = {}): Task {
  return {
    id,
    listId: LIST,
    title: id,
    done: false,
    starred: false,
    position: "a0",
    deletedAt: null,
    updatedAt: "2026-09-21T00:00:00.000Z",
    ...patch,
  };
}

function taskChange(seq: number, entity: Task, key = `k${seq}`): ChangeEnvelope {
  return {
    seq,
    listId: entity.listId,
    idempotencyKey: key,
    deviceId: "other",
    serverTimestamp: "2026-09-21T00:00:00.000Z",
    authorUserId: "other-user",
    entityType: "task",
    entity,
  };
}

function listChange(seq: number, entity: TaskList): ChangeEnvelope {
  return {
    seq,
    listId: entity.id,
    idempotencyKey: `k${seq}`,
    deviceId: "other",
    serverTimestamp: "2026-09-21T00:00:00.000Z",
    authorUserId: "other-user",
    entityType: "list",
    entity,
  };
}

function membership(listId: string, patch: Partial<Membership> = {}): Membership {
  return {
    listId,
    role: "owner",
    position: null,
    memberCount: 1,
    maxSeq: null,
    notify: [],
    ...patch,
  };
}

function recording(): { replica: Replica; batches: WriteBatch[] } {
  const batches: WriteBatch[] = [];
  return { replica: new Replica({ write: (b) => batches.push(b) }), batches };
}

describe("Replica.commit", () => {
  it("writes the entity and its outbox row in one batch (F5.7)", () => {
    const { replica, batches } = recording();
    replica.commit({ tasks: [task("t1")] }, [
      { key: "e1", kind: "task", listId: LIST, entityId: "t1", body: null },
    ]);

    expect(replica.getTask("t1")?.title).toBe("t1");
    expect(replica.nextPending()?.key).toBe("e1");
    expect(batches).toHaveLength(1);
    expect(batches[0]?.putTasks).toHaveLength(1);
    expect(batches[0]?.putOutbox).toHaveLength(1);
  });

  it("drains rows oldest first", () => {
    const replica = new Replica();
    for (const key of ["a", "b", "c"]) {
      replica.commit({}, [{ key, kind: "task", listId: LIST, entityId: key, body: null }]);
    }
    expect(replica.nextPending()?.key).toBe("a");
    replica.completeRow("a", null);
    expect(replica.nextPending()?.key).toBe("b");
  });

  it("keeps a refused row, but stops offering it", () => {
    const replica = new Replica();
    replica.commit({}, [{ key: "a", kind: "task", listId: LIST, entityId: "t", body: null }]);
    replica.markDead("a", "403 forbidden");
    expect(replica.nextPending()).toBeNull();
    expect(replica.stuckCount()).toBe(1);
    expect(replica.hasOutboxFor(LIST)).toBe(true);
  });

  it("stops persisting once closed", () => {
    const { replica, batches } = recording();
    replica.close();
    replica.commit({ tasks: [task("t1")] }, []);
    expect(batches).toHaveLength(0);
  });
});

describe("Replica.apply", () => {
  it("applies in seq order and advances the cursor with the write (F5.8)", () => {
    const { replica, batches } = recording();
    expect(replica.apply(taskChange(1, task("t1")))).toBe("applied");
    expect(replica.cursor(LIST)).toBe(1);
    expect(batches[0]?.putCursors).toEqual([{ listId: LIST, cursor: 1 }]);
  });

  it("treats redelivery as a no-op (F5.2)", () => {
    const replica = new Replica();
    replica.apply(taskChange(1, task("t1", { title: "first" })));
    expect(replica.apply(taskChange(1, task("t1", { title: "again" })))).toBe("already-applied");
    expect(replica.getTask("t1")?.title).toBe("first");
  });

  it("refuses to skip ahead on a gap (F5.6)", () => {
    const replica = new Replica();
    replica.apply(taskChange(1, task("t1")));
    expect(replica.apply(taskChange(3, task("t3")))).toBe("gap");
    expect(replica.getTask("t3")).toBeNull();
    expect(replica.cursor(LIST)).toBe(1);
  });

  it("does not let an echo overwrite an edit still queued for the same task", () => {
    const replica = new Replica();
    replica.apply(taskChange(1, task("t1", { title: "old" })));
    // Two quick renames: the first one's echo arrives while the second is queued.
    replica.commit({ tasks: [task("t1", { title: "one" })] }, [
      { key: "e1", kind: "task", listId: LIST, entityId: "t1", body: null },
    ]);
    replica.commit({ tasks: [task("t1", { title: "two" })] }, [
      { key: "e2", kind: "task", listId: LIST, entityId: "t1", body: null },
    ]);

    replica.completeRow("e1", taskChange(2, task("t1", { title: "one" }), "e1"));
    expect(replica.getTask("t1")?.title).toBe("two");
    expect(replica.cursor(LIST)).toBe(2);

    replica.completeRow("e2", taskChange(3, task("t1", { title: "two" }), "e2"));
    expect(replica.getTask("t1")?.title).toBe("two");
    expect(replica.pendingCount()).toBe(0);
  });

  it("keeps membership fields when the list itself changes (#68)", () => {
    const replica = new Replica();
    replica.noteMemberships([membership(LIST, { position: "a5", memberCount: 2 })]);
    const list: TaskList = {
      id: LIST,
      title: "Shop",
      backgroundPhotoUrl: null,
      deletedAt: null,
      updatedAt: "2026-09-21T00:00:00.000Z",
    };
    replica.apply(listChange(1, list));
    const local = replica.getList(LIST) as LocalList;
    expect(local.list?.title).toBe("Shop");
    expect(local.position).toBe("a5");
    expect(local.memberCount).toBe(2);
    expect(local.role).toBe("owner");
  });
});

describe("Replica.noteMemberships", () => {
  it("forgets a list this account is no longer on, with its tasks (#60)", () => {
    const replica = new Replica();
    replica.noteMemberships([membership(LIST), membership("list-2")]);
    replica.apply(taskChange(1, task("t1")));
    replica.noteMemberships([membership("list-2")]);
    expect(replica.getList(LIST)).toBeNull();
    expect(replica.getTask("t1")).toBeNull();
    expect(replica.hasCursor(LIST)).toBe(false);
  });

  it("keeps a list made offline that the server has not heard of yet", () => {
    const replica = new Replica();
    replica.commit(
      {
        lists: [
          {
            id: LIST,
            list: null,
            role: "owner",
            position: null,
            memberCount: 1,
            notify: [],
            rank: 0,
          },
        ],
      },
      [{ key: "c", kind: "claim", listId: LIST, entityId: LIST, body: null }],
    );
    replica.noteMemberships([]);
    expect(replica.getList(LIST)).not.toBeNull();
  });

  it("adopts a notification choice made elsewhere, and reads an old server's as none", () => {
    const replica = new Replica();
    replica.noteMemberships([membership(LIST, { notify: ["added", "checked"] })]);
    expect(replica.getList(LIST)?.notify).toEqual(["added", "checked"]);

    const old = membership(LIST) as Partial<Membership>;
    delete old.notify;
    replica.noteMemberships([old as Membership]);
    expect(replica.getList(LIST)?.notify).toEqual([]);
  });

  it("keeps a queued notification choice over an answer that has not seen it (ADR 0012)", () => {
    const replica = new Replica();
    replica.noteMemberships([membership(LIST)]);
    const known = replica.getList(LIST) as LocalList;
    replica.commit({ lists: [{ ...known, notify: ["deleted"] }] }, [
      {
        key: "n",
        kind: "notify",
        listId: LIST,
        entityId: `notify:${LIST}`,
        body: { listId: LIST, events: ["deleted"] },
      },
    ]);
    replica.noteMemberships([membership(LIST)]);
    expect(replica.getList(LIST)?.notify).toEqual(["deleted"]);
  });

  it("keeps a queued drag rather than the order the server last had", () => {
    const replica = new Replica();
    replica.noteMemberships([membership(LIST, { position: "a0" })]);
    const known = replica.getList(LIST) as LocalList;
    replica.commit({ lists: [{ ...known, position: "a9" }] }, [
      { key: "o", kind: "order", listId: LIST, entityId: LIST, body: null },
    ]);
    replica.noteMemberships([membership(LIST, { position: "a0" })]);
    expect(replica.getList(LIST)?.position).toBe("a9");
  });

  it("tells an empty answer apart from not having asked", () => {
    const replica = new Replica();
    expect(replica.hasMemberships).toBe(false);
    replica.noteMemberships([]);
    expect(replica.hasMemberships).toBe(true);
  });
});
