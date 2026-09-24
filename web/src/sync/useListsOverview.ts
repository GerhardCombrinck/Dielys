/**
 * The lists screen's data, read from the local replica (`data/replica.ts`):
 * it paints from IndexedDB on open, and `SyncEngine` folds in
 * `/auth/memberships` and each list's changelog behind it. A list's title
 * lives in its own changelog, not in the membership row, so a list is not
 * shown until that has answered at least once — the same "not arrived yet"
 * gate `android/.../ListsViewModel.kt` applies.
 *
 * Writes land locally in the same tick and queue for the server
 * (`data/repository.ts`); the promises resolve immediately.
 */
import type { Membership } from "@dielys/protocol";
import { useCallback, useEffect, useState } from "react";
import { useDataStore, useReplicaVersion } from "../data/store.js";
import { ensureAccents, getAccent, setAccent as storeAccent } from "../domain/accentStore.js";
import { activeTasks, sortLists } from "../domain/taskOrder.js";

export interface ListRow {
  membership: Membership;
  title: string | null; // null until the changelog has answered at least once
  itemCount: number; // open (not done, not deleted) tasks only — matches Daos.kt's count query
  accent: number;
}

export interface ListsOverview {
  /** null until there is anything to show: stored lists, or a first memberships answer. */
  rows: ListRow[] | null;
  refresh(): void;
  createList(title: string): Promise<string>;
  renameList(listId: string, title: string): Promise<void>;
  deleteList(listId: string): Promise<void>;
  moveList(listId: string, afterId: string | null, beforeId: string | null): Promise<void>;
  setAccent(listId: string, accent: number): void;
}

export function useListsOverview(): ListsOverview {
  const { replica, engine, repo } = useDataStore();
  useReplicaVersion(replica);
  // Accents live in localStorage, not the replica — this is what re-renders
  // after one is picked.
  const [, setAccentVersion] = useState(0);

  const lists = sortLists(replica.allLists());
  const ids = lists.map((l) => l.id).join(",");
  useEffect(() => {
    if (ids !== "") ensureAccents(ids.split(","));
  }, [ids]);

  const rows: ListRow[] | null =
    lists.length === 0 && !replica.hasMemberships
      ? null
      : lists.map((local) => ({
          membership: {
            listId: local.id,
            role: local.role ?? "member",
            position: local.position,
            memberCount: local.memberCount,
            maxSeq: null,
            notify: local.notify,
          },
          title: local.list !== null && local.list.deletedAt === null ? local.list.title : null,
          itemCount: activeTasks(replica.tasksIn(local.id)).length,
          accent: getAccent(local.id) ?? 0,
        }));

  const refresh = useCallback(() => engine.request("sync"), [engine]);

  const setAccent = useCallback((listId: string, accent: number) => {
    storeAccent(listId, accent);
    setAccentVersion((v) => v + 1);
  }, []);

  return {
    rows,
    refresh,
    createList: async (title) => {
      const id = repo.createList(title);
      if (id !== "") ensureAccents([id]);
      return id;
    },
    renameList: async (listId, title) => repo.renameList(listId, title),
    deleteList: async (listId) => repo.deleteList(listId),
    moveList: async (listId, afterId, beforeId) => repo.moveList(listId, afterId, beforeId),
    setAccent,
  };
}
