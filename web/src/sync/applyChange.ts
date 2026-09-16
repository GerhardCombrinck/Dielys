/**
 * Folds change envelopes into in-memory state. There is no local database to
 * write into (web/AGENTS.md) — this is the entire "storage layer" for a list:
 * a map keyed by entity id, upserted from whatever the server sends, because
 * a `ChangeEnvelope.entity` is always the full row after the patch applied,
 * never a diff (only `Mutation.patch`, the direction this client writes, is
 * partial).
 */
import type { ChangeEnvelope, Task, TaskList } from "@dielys/protocol";

export interface ListState {
  list: TaskList | null;
  tasksById: Map<string, Task>;
}

export function emptyListState(): ListState {
  return { list: null, tasksById: new Map() };
}

/** Mutates `state` in place — callers own copy-on-write (see the `useState`
 * updaters in `sync/useTaskBoard.ts`). */
export function applyChange(state: ListState, change: ChangeEnvelope): void {
  if (change.entityType === "list") {
    state.list = change.entity;
  } else {
    state.tasksById.set(change.entity.id, change.entity);
  }
}

/** List order is `(position, id)` ascending (F5.5) — the id is what breaks a
 * tie when two devices insert at the same spot offline. */
function sortTasks(tasks: Task[]): Task[] {
  return [...tasks].sort(
    (a, b) =>
      (a.position < b.position ? -1 : a.position > b.position ? 1 : 0) || (a.id < b.id ? -1 : 1),
  );
}

export function activeTasks(state: ListState): Task[] {
  return sortTasks([...state.tasksById.values()].filter((t) => t.deletedAt === null && !t.done));
}

export function doneTasks(state: ListState): Task[] {
  return sortTasks([...state.tasksById.values()].filter((t) => t.deletedAt === null && t.done));
}

export function liveTaskCount(state: ListState): number {
  let count = 0;
  for (const task of state.tasksById.values()) {
    if (task.deletedAt === null) count += 1;
  }
  return count;
}
