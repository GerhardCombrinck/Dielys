/**
 * Tab-lifetime cache for per-list state and the lists overview, so
 * revisiting something already loaded this session paints instantly instead
 * of re-paying a full changelog replay (web/AGENTS.md's "no local replica"
 * still holds — this is memory, not storage; an F5 reload starts fresh same
 * as before). Shared by `useListsOverview`, `useTaskBoard`, and `HomePage`'s
 * hover prefetch, so a list only ever pays a cold `since=0` catch-up once
 * per session no matter which of those three reaches it first. Cleared on
 * sign-out (`SessionContext.tsx`) so the next session never sees a previous
 * account's data.
 */
import { catchUp } from "../api/lists.js";
import { applyChange, emptyListState, type ListState } from "./applyChange.js";
import { hydrateList } from "./hydrateList.js";
import type { ListRow } from "./useListsOverview.js";

export interface BoardCacheEntry {
  state: ListState;
  cursor: number;
}

let overviewCache: ListRow[] | null = null;
const boardCache = new Map<string, BoardCacheEntry>();
const inFlight = new Map<string, Promise<BoardCacheEntry>>();

export function getOverviewCache(): ListRow[] | null {
  return overviewCache;
}

export function setOverviewCache(rows: ListRow[]): void {
  overviewCache = rows;
}

export function getBoardCache(listId: string): BoardCacheEntry | null {
  return boardCache.get(listId) ?? null;
}

/** Written from `useTaskBoard`'s own merge path (a socket push or this
 * device's mutation ack) so the cache never drifts from what the page most
 * recently rendered. */
export function updateBoardCache(listId: string, state: ListState, cursor: number): void {
  boardCache.set(listId, { state, cursor });
}

/** First load of a list this session: a cache hit, an in-flight prefetch
 * already running, or a fresh full catch-up (`since=0`) — never more than
 * one of the latter in flight per list at a time. */
export function ensureListLoaded(listId: string): Promise<BoardCacheEntry> {
  const cached = boardCache.get(listId);
  if (cached !== undefined) return Promise.resolve(cached);
  const existing = inFlight.get(listId);
  if (existing !== undefined) return existing;

  const promise = (async (): Promise<BoardCacheEntry> => {
    const state = emptyListState();
    const cursor = await hydrateList(listId, state);
    const entry = { state, cursor };
    boardCache.set(listId, entry);
    return entry;
  })();
  inFlight.set(listId, promise);
  promise.then(
    () => inFlight.delete(listId),
    () => inFlight.delete(listId),
  );
  return promise;
}

/** Hover/pointerdown prefetch (`HomePage`'s list rows) — fire-and-forget;
 * whichever hook opens the list next just finds it already cached. */
export function prefetchList(listId: string): void {
  ensureListLoaded(listId).catch(() => {
    // The eventual real open retries via ensureListLoaded/reconcileList.
  });
}

/** A list already in cache: pull only what changed since its cached cursor
 * instead of replaying the whole changelog again. Never mutates the cached
 * entry in place — a fresh state is built and swapped in, the same
 * copy-on-write rule `applyChange.ts` asks callers to follow, so a
 * `useTaskBoard` that is currently holding the old cached object as its
 * React state is never mutated out from under it. */
export async function reconcileList(listId: string): Promise<BoardCacheEntry> {
  const cached = boardCache.get(listId);
  if (cached === undefined) return ensureListLoaded(listId);

  const state: ListState = { list: cached.state.list, tasksById: new Map(cached.state.tasksById) };
  let cursor = cached.cursor;
  for (;;) {
    const response = await catchUp(listId, cursor);
    for (const change of response.changes) applyChange(state, change);
    const last = response.changes.at(-1);
    if (!response.truncated || last === undefined) break;
    cursor = last.seq;
  }
  const entry = { state, cursor };
  boardCache.set(listId, entry);
  return entry;
}

export function clearListCache(): void {
  overviewCache = null;
  boardCache.clear();
  inFlight.clear();
}
