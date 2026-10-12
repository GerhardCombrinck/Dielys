import type { TaskMutation } from "@dielys/protocol";
import { describe, expect, it } from "vitest";
import { activeTasks } from "../domain/taskOrder.js";
import { Replica, type WriteBatch } from "./replica.js";
import { Repository } from "./repository.js";

function setUp(): { repo: Repository; replica: Replica; batches: WriteBatch[] } {
  const batches: WriteBatch[] = [];
  const replica = new Replica({ write: (b) => batches.push(b) });
  let clock = Date.parse("2026-10-12T08:00:00.000Z");
  const repo = new Repository(
    replica,
    "device-a",
    () => {},
    () => clock++,
  );
  return { repo, replica, batches };
}

describe("Repository.moveTaskToList (ADR 0015)", () => {
  it("creates the task on the target and tombstones it here, in one commit", () => {
    const { repo, replica, batches } = setUp();
    const groceries = repo.createList("Groceries");
    const hardware = repo.createList("Hardware");
    const bulbs = repo.addTask(groceries, "Light bulbs", true);
    repo.addTask(groceries, "Milk", true);
    const before = batches.length;

    const moved = repo.moveTaskToList(bulbs, hardware, true);

    // A fresh id: the old one stays tombstoned (F5.3).
    expect(moved).not.toBe("");
    expect(moved).not.toBe(bulbs);
    expect(activeTasks(replica.tasksIn(groceries)).map((t) => t.title)).toEqual(["Milk"]);
    expect(activeTasks(replica.tasksIn(hardware)).map((t) => t.title)).toEqual(["Light bulbs"]);

    // Both halves in one batch (F5.7).
    expect(batches).toHaveLength(before + 1);
    const rows = batches.at(-1)?.putOutbox ?? [];
    const bodies = rows.map((row) => row.body as TaskMutation);
    expect(bodies.map((b) => [b.listId, b.entityId])).toEqual([
      [hardware, moved],
      [groceries, bulbs],
    ]);
    expect(bodies[0]?.patch).toMatchObject({ title: "Light bulbs" });
    expect(bodies[1]?.patch.deletedAt).toEqual(expect.any(String));
  });

  it("keeps a star and puts the task at the top", () => {
    const { repo, replica } = setUp();
    const groceries = repo.createList("Groceries");
    const hardware = repo.createList("Hardware");
    repo.addTask(hardware, "Screws", false);
    repo.addTask(hardware, "Paint", false);
    const bulbs = repo.addTask(groceries, "Light bulbs", true);
    repo.setStarred(bulbs, true);

    repo.moveTaskToList(bulbs, hardware, false);

    const first = activeTasks(replica.tasksIn(hardware))[0];
    expect(first?.title).toBe("Light bulbs");
    expect(first?.starred).toBe(true);
  });

  it("does nothing when the task is already on that list", () => {
    const { repo, batches } = setUp();
    const groceries = repo.createList("Groceries");
    const milk = repo.addTask(groceries, "Milk", true);
    const before = batches.length;

    expect(repo.moveTaskToList(milk, groceries, true)).toBe("");
    expect(batches).toHaveLength(before);
  });
});
