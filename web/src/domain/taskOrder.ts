/**
 * How tasks and lists are ordered on screen. Pure — no DOM, no storage (D1).
 */
import type { Task } from "@dielys/protocol";

function compare(a: string, b: string): number {
  return a < b ? -1 : a > b ? 1 : 0;
}

/** `(position, id)` ascending (F5.5) — the id breaks a tie when two devices insert at the same spot offline. */
function sortTasks(tasks: Task[]): Task[] {
  return [...tasks].sort((a, b) => compare(a.position, b.position) || compare(a.id, b.id));
}

export function activeTasks(tasks: Task[]): Task[] {
  return sortTasks(tasks.filter((t) => t.deletedAt === null && !t.done));
}

export function doneTasks(tasks: Task[]): Task[] {
  return sortTasks(tasks.filter((t) => t.deletedAt === null && t.done));
}

interface OrderedList {
  id: string;
  position: string | null;
  rank: number;
}

/**
 * This account's list order, as the server would answer it
 * (PROTOCOL.md "Ordering the lists"): positioned lists first by position,
 * then the unpositioned ones in the order the last memberships answer gave
 * them (`rank`), with lists made here and not yet on the server last.
 */
export function sortLists<T extends OrderedList>(lists: T[]): T[] {
  return [...lists].sort((a, b) => {
    if (a.position !== null && b.position !== null) {
      return compare(a.position, b.position) || compare(a.id, b.id);
    }
    if (a.position !== null) return -1;
    if (b.position !== null) return 1;
    return a.rank - b.rank || compare(a.id, b.id);
  });
}
