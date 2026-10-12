/**
 * One list's board, read from the local replica (`data/replica.ts`) — so it
 * paints from IndexedDB on open, before any request has answered — plus a
 * catch-up and a live socket for whatever changes while the page is open
 * (ADR 0009).
 *
 * Every write lands in the replica and the outbox in the same tick as the
 * tap, and `SyncEngine` sends it after; nothing here waits on the network
 * (docs/SYNC.md, the same model as `android/.../DielysRepository.kt`). The
 * methods still return promises only so callers that `await` them keep
 * working — they resolve immediately.
 */
import type { NotifyEvent, Task, TaskList } from "@dielys/protocol";
import { useEffect, useState } from "react";
import { ListSocket } from "../api/socket.js";
import { useDataStore, useReplicaVersion } from "../data/store.js";
import { activeTasks, doneTasks, sortLists } from "../domain/taskOrder.js";

/** A list a task here could be moved to (ADR 0015). */
export interface MoveTarget {
  id: string;
  title: string;
}

export interface TaskBoard {
  list: TaskList | null;
  active: Task[];
  done: Task[];
  loaded: boolean;
  /** Somebody else is on this list, so there is something to be notified about. */
  shared: boolean;
  /** This account's notification choice for the list (ADR 0012). */
  notify: NotifyEvent[];
  setNotify(events: NotifyEvent[]): void;
  add(title: string, atTop: boolean): Promise<void>;
  setDone(task: Task, done: boolean): Promise<void>;
  setStarred(task: Task, starred: boolean): Promise<void>;
  rename(task: Task, title: string): Promise<void>;
  remove(task: Task): Promise<void>;
  move(taskId: string, afterId: string | null, beforeId: string | null): Promise<void>;
  /** Every other list still in use, in the Lists page's order — archived
   * ones left out, since moving onto a list that is put away would hide it. */
  moveTargets: MoveTarget[];
  moveToList(task: Task, targetListId: string, atTop: boolean): void;
  renameList(title: string): Promise<void>;
  deleteList(): Promise<void>;
}

export function useTaskBoard(listId: string, deviceId: string): TaskBoard {
  const { replica, engine, repo } = useDataStore();
  useReplicaVersion(replica);
  // Set once this visit's first catch-up has answered (or failed), so a list
  // this browser has never seen shows a spinner rather than "empty" until then.
  const [pulledId, setPulledId] = useState<string | null>(null);
  // A list created a moment ago has no changelog on the server until its
  // claim lands; asking before then only earns a 403. This flips (and the
  // effect below runs) the moment the drain gets the claim through.
  const unclaimed = replica.isUnclaimed(listId);

  useEffect(() => {
    if (unclaimed) return;
    let cancelled = false;
    void engine.catchUp(listId).finally(() => {
      if (!cancelled) setPulledId(listId);
    });

    // The socket is a latency optimisation only: everything it delivers goes
    // through the same `Replica.apply` a catch-up does (F5.6), and a gap it
    // reveals is pulled over HTTP.
    const socket = new ListSocket(listId, deviceId, () => replica.cursor(listId), {
      // Nothing renders connection status (it read as an irritating
      // "Connecting…" chip with data already on screen).
      onStatus: () => {},
      onChange: (change) => {
        if (!cancelled) void engine.receive(change);
      },
      onHelloOk: (maxSeq) => {
        if (!cancelled && maxSeq > replica.cursor(listId)) void engine.catchUp(listId);
      },
    });
    socket.start();

    return () => {
      cancelled = true;
      socket.stop();
    };
  }, [listId, deviceId, replica, engine, unclaimed]);

  const local = replica.getList(listId);
  const moveTargets = sortLists(replica.allLists()).flatMap((other) =>
    other.id !== listId &&
    other.list !== null &&
    other.list.deletedAt === null &&
    !other.list.archived
      ? [{ id: other.id, title: other.list.title }]
      : [],
  );
  const tasks = replica.tasksIn(listId);

  return {
    list: local?.list ?? null,
    active: activeTasks(tasks),
    done: doneTasks(tasks),
    loaded: pulledId === listId || local?.list != null || replica.hasCursor(listId) || unclaimed,
    shared: (local?.memberCount ?? 1) > 1,
    notify: local?.notify ?? [],
    setNotify: (events) => repo.setNotify(listId, events),
    add: async (title, atTop) => {
      repo.addTask(listId, title, atTop);
    },
    setDone: async (task, done) => repo.setDone(task.id, done),
    setStarred: async (task, starred) => repo.setStarred(task.id, starred),
    rename: async (task, title) => repo.renameTask(task.id, title),
    remove: async (task) => repo.deleteTask(task.id),
    move: async (taskId, afterId, beforeId) => repo.moveTask(taskId, afterId, beforeId),
    moveTargets,
    moveToList: (task, targetListId, atTop) => {
      repo.moveTaskToList(task.id, targetListId, atTop);
    },
    renameList: async (title) => repo.renameList(listId, title),
    deleteList: async () => repo.deleteList(listId),
  };
}
