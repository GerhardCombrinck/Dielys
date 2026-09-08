import type { Task, TaskList } from "@dielys/protocol";
import { describe, expect, it } from "vitest";
import { applyListPatch, applyTaskPatch, type FieldMetaMap } from "../src/domain/apply.js";

const T1 = "2026-01-01T00:00:00.000Z";
const T2 = "2026-01-01T00:00:01.000Z";

const task: Task = {
  id: "task-1",
  listId: "list-1",
  title: "Milk",
  done: false,
  starred: false,
  position: "a0",
  deletedAt: null,
  updatedAt: T1,
};

function metaFor(fields: Record<string, [string, string]>): FieldMetaMap {
  return Object.fromEntries(
    Object.entries(fields).map(([field, [serverTimestamp, deviceId]]) => [
      field,
      { serverTimestamp, deviceId },
    ]),
  );
}

describe("applyTaskPatch — creates (F5.1)", () => {
  it("creates from a patch carrying title and position", () => {
    const result = applyTaskPatch(
      null,
      {},
      { title: "Bread", position: "a1" },
      { entityId: "task-2", listId: "list-1" },
      { serverTimestamp: T1, deviceId: "d1" },
    );
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    // The id is the client's, never minted here (F5.1).
    expect(result.entity.id).toBe("task-2");
    expect(result.entity.updatedAt).toBe(T1);
    expect(result.entity.done).toBe(false);
  });

  it("rejects a create with no title", () => {
    const result = applyTaskPatch(
      null,
      {},
      { position: "a1" },
      { entityId: "task-2", listId: "list-1" },
      { serverTimestamp: T1, deviceId: "d1" },
    );
    expect(result).toEqual({ ok: false, code: "incomplete-create" });
  });

  it("rejects a create with no position", () => {
    const result = applyTaskPatch(
      null,
      {},
      { title: "Bread" },
      { entityId: "task-2", listId: "list-1" },
      { serverTimestamp: T1, deviceId: "d1" },
    );
    expect(result).toEqual({ ok: false, code: "incomplete-create" });
  });
});

describe("applyTaskPatch — per-field last-write-wins (F5.4)", () => {
  it("H3.5: two devices ticking the same task converge without error", () => {
    const first = applyTaskPatch(
      task,
      {},
      { done: true },
      { entityId: task.id, listId: task.listId },
      { serverTimestamp: T1, deviceId: "d1" },
    );
    expect(first.ok).toBe(true);
    if (!first.ok) return;

    const second = applyTaskPatch(
      first.entity,
      metaFor({ done: [T1, "d1"] }),
      { done: true },
      { entityId: task.id, listId: task.listId },
      { serverTimestamp: T2, deviceId: "d2" },
    );
    expect(second.ok).toBe(true);
    if (!second.ok) return;
    expect(second.entity.done).toBe(true);
  });

  it("a rename and a tick from two devices both survive", () => {
    // The whole reason patches are per-field: row-level LWW would drop one.
    const renamed = applyTaskPatch(
      task,
      {},
      { title: "Melk" },
      { entityId: task.id, listId: task.listId },
      { serverTimestamp: T1, deviceId: "d1" },
    );
    expect(renamed.ok).toBe(true);
    if (!renamed.ok) return;

    const ticked = applyTaskPatch(
      renamed.entity,
      metaFor({ title: [T1, "d1"] }),
      { done: true },
      { entityId: task.id, listId: task.listId },
      { serverTimestamp: T2, deviceId: "d2" },
    );
    expect(ticked.ok).toBe(true);
    if (!ticked.ok) return;
    expect(ticked.entity.title).toBe("Melk");
    expect(ticked.entity.done).toBe(true);
  });

  it("an older write loses to the field's existing provenance", () => {
    const result = applyTaskPatch(
      { ...task, title: "Melk" },
      metaFor({ title: [T2, "d1"] }),
      { title: "stale" },
      { entityId: task.id, listId: task.listId },
      { serverTimestamp: T1, deviceId: "d2" },
    );
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.entity.title).toBe("Melk");
    expect(result.wrote).toEqual([]);
    // Nothing changed hands, so the row's updatedAt must not move either.
    expect(result.entity.updatedAt).toBe(task.updatedAt);
  });

  it("H3.8: a tie on server timestamp breaks on device id, not on any client clock", () => {
    // Both mutations land in the same DO turn, where Date.now() is frozen —
    // this is the live path, not a theoretical one.
    const lower = applyTaskPatch(
      { ...task, title: "from-d2" },
      metaFor({ title: [T1, "d2"] }),
      { title: "from-d1" },
      { entityId: task.id, listId: task.listId },
      { serverTimestamp: T1, deviceId: "d1" },
    );
    expect(lower.ok).toBe(true);
    if (!lower.ok) return;
    expect(lower.entity.title).toBe("from-d2");

    const higher = applyTaskPatch(
      { ...task, title: "from-d1" },
      metaFor({ title: [T1, "d1"] }),
      { title: "from-d2" },
      { entityId: task.id, listId: task.listId },
      { serverTimestamp: T1, deviceId: "d2" },
    );
    expect(higher.ok).toBe(true);
    if (!higher.ok) return;
    expect(higher.entity.title).toBe("from-d2");
  });

  it("a field nobody has written yet is always taken", () => {
    const result = applyTaskPatch(
      task,
      {},
      { starred: true },
      { entityId: task.id, listId: task.listId },
      { serverTimestamp: T1, deviceId: "d1" },
    );
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.entity.starred).toBe(true);
    expect(result.wrote).toEqual(["starred"]);
  });
});

describe("applyTaskPatch — tombstones (F5.3)", () => {
  const tombstoned: Task = { ...task, deletedAt: T1, updatedAt: T1 };

  it("H3.7: a later update does not resurrect a deleted row", () => {
    const result = applyTaskPatch(
      tombstoned,
      metaFor({ deletedAt: [T1, "d1"] }),
      { deletedAt: null, title: "back from the dead" },
      { entityId: task.id, listId: task.listId },
      { serverTimestamp: T2, deviceId: "d2" },
    );
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    // The tombstone is sticky even though the incoming write is newer.
    expect(result.entity.deletedAt).toBe(T1);
    // Other fields still land — only the resurrection is refused.
    expect(result.entity.title).toBe("back from the dead");
  });

  it("deleting twice keeps the row deleted", () => {
    const result = applyTaskPatch(
      tombstoned,
      metaFor({ deletedAt: [T1, "d1"] }),
      { deletedAt: T2 },
      { entityId: task.id, listId: task.listId },
      { serverTimestamp: T2, deviceId: "d2" },
    );
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.entity.deletedAt).toBe(T2);
  });

  it("a fresh delete sets the tombstone rather than removing the row", () => {
    const result = applyTaskPatch(
      task,
      {},
      { deletedAt: T2 },
      { entityId: task.id, listId: task.listId },
      { serverTimestamp: T2, deviceId: "d1" },
    );
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.entity.deletedAt).toBe(T2);
    expect(result.entity.title).toBe("Milk");
  });
});

describe("applyListPatch", () => {
  const list: TaskList = {
    id: "list-1",
    title: "Groceries",
    backgroundPhotoUrl: null,
    deletedAt: null,
    updatedAt: T1,
  };

  it("creates from a patch carrying a title", () => {
    const result = applyListPatch(
      null,
      {},
      { title: "Groceries" },
      { entityId: "list-1" },
      { serverTimestamp: T1, deviceId: "d1" },
    );
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.entity).toEqual(list);
  });

  it("rejects a create with no title", () => {
    const result = applyListPatch(
      null,
      {},
      { backgroundPhotoUrl: null },
      { entityId: "list-1" },
      { serverTimestamp: T1, deviceId: "d1" },
    );
    expect(result).toEqual({ ok: false, code: "incomplete-create" });
  });

  it("keeps a list tombstone sticky too", () => {
    const result = applyListPatch(
      { ...list, deletedAt: T1 },
      metaFor({ deletedAt: [T1, "d1"] }),
      { deletedAt: null },
      { entityId: "list-1" },
      { serverTimestamp: T2, deviceId: "d2" },
    );
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.entity.deletedAt).toBe(T1);
  });

  it("clears a background photo when asked", () => {
    const result = applyListPatch(
      { ...list, backgroundPhotoUrl: "https://example.test/a.jpg" },
      metaFor({ backgroundPhotoUrl: [T1, "d1"] }),
      { backgroundPhotoUrl: null },
      { entityId: "list-1" },
      { serverTimestamp: T2, deviceId: "d2" },
    );
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    // null means "cleared" for this field; only deletedAt treats it specially.
    expect(result.entity.backgroundPhotoUrl).toBeNull();
  });
});
