/**
 * The lists screen's data, fed by a fresh `/auth/memberships` plus one
 * catch-up per list (there is no local database to read instead —
 * web/AGENTS.md). A list's title lives in its own changelog, not in the
 * membership row, so a row is not shown until that pull has answered — the
 * same "not arrived yet" gate `android/.../ListsViewModel.kt` applies, just
 * without a Room table to detect it from.
 */
import type { ListMutation, Membership } from "@dielys/protocol";
import { PROTOCOL_VERSION } from "@dielys/protocol";
import { useCallback, useEffect, useRef, useState } from "react";
import { claimList, getMemberships, mutate, setListPosition } from "../api/lists.js";
import { ensureAccents, getAccent, setAccent as storeAccent } from "../domain/accentStore.js";
import { seedPositions } from "../domain/listOrder.js";
import { between } from "../domain/position.js";
import { uuid7 } from "../domain/uuid7.js";
import { activeTasks, emptyListState, type ListState } from "./applyChange.js";
import { getBoardCache, getOverviewCache, reconcileList, setOverviewCache } from "./listCache.js";

export interface ListRow {
  membership: Membership;
  title: string | null; // null until the changelog has answered at least once
  itemCount: number; // open (not done, not deleted) tasks only — matches Daos.kt's count query
  accent: number;
}

export interface ListsOverview {
  /** null while the first load is in flight. */
  rows: ListRow[] | null;
  refresh(): void;
  createList(title: string): Promise<string>;
  renameList(listId: string, title: string): Promise<void>;
  deleteList(listId: string): Promise<void>;
  moveList(listId: string, afterId: string | null, beforeId: string | null): Promise<void>;
  setAccent(listId: string, accent: number): void;
}

export function useListsOverview(deviceId: string): ListsOverview {
  // Seeded from last session's cache, if any (listCache.ts), so a repeat
  // visit to the overview paints immediately instead of a "Loading…" flash
  // — `load()` below still runs and reconciles it in the background.
  const [rows, setRows] = useState<ListRow[] | null>(getOverviewCache);
  // Guards a `refresh()` call (or the mount load) that is still in flight
  // when this hook's owner unmounts, so it does not set state on a dead page.
  const aliveRef = useRef(true);
  useEffect(() => {
    aliveRef.current = true;
    return () => {
      aliveRef.current = false;
    };
  }, []);

  const load = useCallback(async (): Promise<void> => {
    const memberships = await getMemberships();
    ensureAccents(memberships.map((m) => m.listId));

    const hydrated = await Promise.all(
      memberships.map(async (membership): Promise<ListRow> => {
        // A cached list (from a previous visit here, an open task board, or
        // a hover prefetch) only needs its delta pulled, not a full replay
        // (listCache.ts's `reconcileList`) — a list never seen this session
        // still gets one full catch-up the same as before.
        let state: ListState = getBoardCache(membership.listId)?.state ?? emptyListState();
        try {
          state = (await reconcileList(membership.listId)).state;
        } catch {
          // Offline or the room is unreachable — show the row with
          // whatever we have (cache, or nothing) rather than dropping it,
          // and `refresh()` (window focus, or leaving a list) tries again.
        }
        const list = state.list;
        return {
          membership,
          title: list !== null && list.deletedAt === null ? list.title : null,
          itemCount: activeTasks(state).length,
          accent: getAccent(membership.listId) ?? 0,
        };
      }),
    );

    if (aliveRef.current) {
      setRows(hydrated);
      setOverviewCache(hydrated);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const refresh = useCallback(() => {
    void load();
  }, [load]);

  const createList = useCallback(
    async (title: string): Promise<string> => {
      const trimmed = title.trim();
      if (trimmed === "") return "";

      // List ids are client-generated (F5.1): claim it, then name it.
      const id = uuid7();
      await claimList(id);
      const mutation: ListMutation = {
        type: "mutate",
        protocolVersion: PROTOCOL_VERSION,
        listId: id,
        idempotencyKey: uuid7(),
        deviceId,
        entityId: id,
        entityType: "list",
        patch: { title: trimmed },
      };
      await mutate(id, mutation);
      ensureAccents([id]);
      refresh();
      return id;
    },
    [deviceId, refresh],
  );

  const renameList = useCallback(
    async (listId: string, title: string): Promise<void> => {
      const trimmed = title.trim();
      if (trimmed === "") return;
      const mutation: ListMutation = {
        type: "mutate",
        protocolVersion: PROTOCOL_VERSION,
        listId,
        idempotencyKey: uuid7(),
        deviceId,
        entityId: listId,
        entityType: "list",
        patch: { title: trimmed },
      };
      await mutate(listId, mutation);
      refresh();
    },
    [deviceId, refresh],
  );

  const deleteList = useCallback(
    async (listId: string): Promise<void> => {
      const mutation: ListMutation = {
        type: "mutate",
        protocolVersion: PROTOCOL_VERSION,
        listId,
        idempotencyKey: uuid7(),
        deviceId,
        entityId: listId,
        entityType: "list",
        patch: { deletedAt: new Date().toISOString() },
      };
      await mutate(listId, mutation);
      refresh();
    },
    [deviceId, refresh],
  );

  // Not a list mutation: this is the caller's own membership ordering
  // (PROTOCOL.md "Ordering the lists"), mirroring
  // `DielysRepository.moveList` including its lazy seeding of never-ordered
  // rows, in the order they are already shown.
  const moveList = useCallback(
    async (listId: string, afterId: string | null, beforeId: string | null): Promise<void> => {
      if (rows === null) return;
      const ordered = rows.map((r) => r.membership);
      if (!ordered.some((m) => m.listId === listId)) return;

      const seeded = seedPositions(
        ordered,
        (m) => m.position,
        (m, position) => ({ ...m, position }),
      );
      const byId = new Map(seeded.map((m) => [m.listId, m]));
      const moved = byId.get(listId);
      if (moved === undefined) return;
      const after = afterId !== null ? (byId.get(afterId) ?? null) : null;
      const before = beforeId !== null ? (byId.get(beforeId) ?? null) : null;
      const position = between(after?.position ?? null, before?.position ?? null);

      const unseeded = new Set(ordered.filter((m) => m.position === null).map((m) => m.listId));
      const seedWrites = seeded.filter((m) => unseeded.has(m.listId) && m.listId !== listId);

      for (const m of seedWrites) {
        if (m.position !== null) await setListPosition(m.listId, m.position);
      }
      await setListPosition(listId, position);
      refresh();
    },
    [rows, refresh],
  );

  const setAccent = useCallback(
    (listId: string, accent: number): void => {
      storeAccent(listId, accent);
      refresh();
    },
    [refresh],
  );

  return { rows, refresh, createList, renameList, deleteList, moveList, setAccent };
}
