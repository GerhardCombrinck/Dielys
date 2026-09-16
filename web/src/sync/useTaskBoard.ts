/**
 * One list's live board: an initial catch-up, then a socket for whatever
 * changes while the page stays open (ADR 0009). No local database — a reload
 * re-fetches everything and starts a fresh in-memory cursor (web/AGENTS.md).
 *
 * Every write here waits for the server's ack before the screen updates —
 * there is no outbox to apply a change locally ahead of it, unlike
 * `android/.../DielysRepository.kt`. That is the online-first trade-off: a
 * tap shows up a network round trip later instead of immediately, and a
 * failed one is a visible error instead of a queued retry.
 */
import type {
  ChangeEnvelope,
  ListMutation,
  ListPatch,
  Task,
  TaskList,
  TaskMutation,
  TaskPatch,
} from "@dielys/protocol";
import { PROTOCOL_VERSION } from "@dielys/protocol";
import { useCallback, useEffect, useRef, useState } from "react";
import { catchUp, mutate } from "../api/lists.js";
import { type ConnectionStatus, ListSocket } from "../api/socket.js";
import { between } from "../domain/position.js";
import { spotUnderStarred } from "../domain/taskPlacement.js";
import { uuid7 } from "../domain/uuid7.js";
import {
  activeTasks,
  applyChange,
  doneTasks,
  emptyListState,
  type ListState,
} from "./applyChange.js";
import { hydrateList } from "./hydrateList.js";

export type { ConnectionStatus } from "../api/socket.js";

export interface TaskBoard {
  list: TaskList | null;
  active: Task[];
  done: Task[];
  loaded: boolean;
  status: ConnectionStatus;
  add(title: string, atTop: boolean): Promise<void>;
  setDone(task: Task, done: boolean): Promise<void>;
  setStarred(task: Task, starred: boolean): Promise<void>;
  rename(task: Task, title: string): Promise<void>;
  remove(task: Task): Promise<void>;
  move(taskId: string, afterId: string | null, beforeId: string | null): Promise<void>;
  renameList(title: string): Promise<void>;
  deleteList(): Promise<void>;
}

export function useTaskBoard(listId: string, deviceId: string): TaskBoard {
  const [state, setState] = useState<ListState>(emptyListState);
  const [loaded, setLoaded] = useState(false);
  const [status, setStatus] = useState<ConnectionStatus>("connecting");
  const cursorRef = useRef(0);
  const stateRef = useRef(state);
  stateRef.current = state;

  const mergeChange = useCallback((change: ChangeEnvelope) => {
    cursorRef.current = Math.max(cursorRef.current, change.seq);
    setState((prev) => {
      const next: ListState = { list: prev.list, tasksById: new Map(prev.tasksById) };
      applyChange(next, change);
      return next;
    });
  }, []);

  useEffect(() => {
    let cancelled = false;
    setState(emptyListState());
    setLoaded(false);
    cursorRef.current = 0;
    setStatus("connecting");

    (async () => {
      const fresh = emptyListState();
      let maxSeq = 0;
      try {
        maxSeq = await hydrateList(listId, fresh);
      } catch {
        // Offline on first load — the socket below still tries, and once it
        // connects a `hello-ok` with a higher maxSeq than our cursor (0)
        // triggers the same gap catch-up a dropped connection would.
      }
      if (cancelled) return;
      setState(fresh);
      cursorRef.current = maxSeq;
      setLoaded(true);
    })();

    const socket = new ListSocket(listId, deviceId, () => cursorRef.current, {
      onStatus: (s) => {
        if (!cancelled) setStatus(s);
      },
      onChange: (change) => {
        if (!cancelled) mergeChange(change);
      },
      // A gap between the last HTTP catch-up (this load, or the previous
      // connection's) and what `hello-ok` says the changelog now reaches —
      // pulled over HTTP rather than a WS catch-up frame, so there is one
      // gap-fill code path, not two (F5.6).
      onHelloOk: (maxSeq) => {
        if (cancelled || maxSeq <= cursorRef.current) return;
        fillGap(listId, cursorRef.current, mergeChange).catch(() => {
          // The next `hello-ok` (this reconnect or the next one) retries.
        });
      },
    });
    socket.start();

    return () => {
      cancelled = true;
      socket.stop();
    };
  }, [listId, deviceId, mergeChange]);

  const sendTask = useCallback(
    async (entityId: string, patch: TaskPatch): Promise<void> => {
      const mutation: TaskMutation = {
        type: "mutate",
        protocolVersion: PROTOCOL_VERSION,
        listId,
        idempotencyKey: uuid7(),
        deviceId,
        entityId,
        entityType: "task",
        patch,
      };
      const ack = await mutate(listId, mutation);
      mergeChange(ack.change);
    },
    [listId, deviceId, mergeChange],
  );

  const sendList = useCallback(
    async (entityId: string, patch: ListPatch): Promise<void> => {
      const mutation: ListMutation = {
        type: "mutate",
        protocolVersion: PROTOCOL_VERSION,
        listId,
        idempotencyKey: uuid7(),
        deviceId,
        entityId,
        entityType: "list",
        patch,
      };
      const ack = await mutate(listId, mutation);
      mergeChange(ack.change);
    },
    [listId, deviceId, mergeChange],
  );

  const add = useCallback(
    async (title: string, atTop: boolean): Promise<void> => {
      const trimmed = title.trim();
      if (trimmed === "") return;
      const active = activeTasks(stateRef.current);
      let position: string;
      if (atTop) {
        const spot = spotUnderStarred(active, (t) => t.starred);
        position = between(active[spot - 1]?.position ?? null, active[spot]?.position ?? null);
      } else {
        position = between(active.at(-1)?.position ?? null, null);
      }
      await sendTask(uuid7(), { title: trimmed, position });
    },
    [sendTask],
  );

  const setDone = useCallback(
    async (task: Task, done: boolean): Promise<void> => {
      if (!done) {
        await sendTask(task.id, { done: false });
        return;
      }
      const firstDone = doneTasks(stateRef.current).at(0) ?? null;
      const position =
        firstDone === null || firstDone.id === task.id
          ? task.position
          : between(null, firstDone.position);
      await sendTask(task.id, { done: true, position });
    },
    [sendTask],
  );

  const setStarred = useCallback(
    async (task: Task, starred: boolean): Promise<void> => {
      if (!starred) {
        await sendTask(task.id, { starred: false });
        return;
      }
      const first = activeTasks(stateRef.current).at(0) ?? null;
      const position =
        first === null || first.id === task.id ? task.position : between(null, first.position);
      await sendTask(task.id, { starred: true, position });
    },
    [sendTask],
  );

  const rename = useCallback(
    async (task: Task, title: string): Promise<void> => {
      const trimmed = title.trim();
      if (trimmed === "") return;
      await sendTask(task.id, { title: trimmed });
    },
    [sendTask],
  );

  const remove = useCallback(
    async (task: Task): Promise<void> => {
      await sendTask(task.id, { deletedAt: new Date().toISOString() });
    },
    [sendTask],
  );

  const move = useCallback(
    async (taskId: string, afterId: string | null, beforeId: string | null): Promise<void> => {
      const after = afterId !== null ? (stateRef.current.tasksById.get(afterId) ?? null) : null;
      const before = beforeId !== null ? (stateRef.current.tasksById.get(beforeId) ?? null) : null;
      const position = between(after?.position ?? null, before?.position ?? null);
      await sendTask(taskId, { position });
    },
    [sendTask],
  );

  const renameList = useCallback(
    async (title: string): Promise<void> => {
      const trimmed = title.trim();
      const current = stateRef.current.list;
      if (trimmed === "" || current === null) return;
      await sendList(current.id, { title: trimmed });
    },
    [sendList],
  );

  const deleteList = useCallback(async (): Promise<void> => {
    const current = stateRef.current.list;
    if (current === null) return;
    await sendList(current.id, { deletedAt: new Date().toISOString() });
  }, [sendList]);

  return {
    list: state.list,
    active: activeTasks(state),
    done: doneTasks(state),
    loaded,
    status,
    add,
    setDone,
    setStarred,
    rename,
    remove,
    move,
    renameList,
    deleteList,
  };
}

/** Pages `catchUp` from `since`, merging every change it finds. */
async function fillGap(
  listId: string,
  since: number,
  merge: (change: ChangeEnvelope) => void,
): Promise<void> {
  let cursor = since;
  for (;;) {
    const response = await catchUp(listId, cursor);
    for (const change of response.changes) merge(change);
    const last = response.changes.at(-1);
    if (!response.truncated || last === undefined) break;
    cursor = last.seq;
  }
}
