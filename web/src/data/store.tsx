/**
 * Wires the replica, its IndexedDB persistence, the sync engine and the
 * repository together for one signed-in account, and hands them to React.
 *
 * - Opened once per account (`DataProvider`, mounted inside the sign-in
 *   gate). Stored data that belongs to a different account is wiped before
 *   anything reads it, so one browser shared by two people never shows the
 *   first one's lists to the second.
 * - Erased on sign-out (`closeStore(true)`), same as the old tab cache was.
 * - Other tabs of the same browser share the database; a `BroadcastChannel`
 *   ping after each write makes them reload it, so an item added in one tab
 *   shows up in the other.
 * - A browser that refuses IndexedDB (some private modes) still works — it
 *   just keeps everything in memory, which is what this client did before.
 */
import {
  createContext,
  type ReactNode,
  useContext,
  useEffect,
  useState,
  useSyncExternalStore,
} from "react";
import { catchUp, claimList, getMemberships, mutate, setListPosition } from "../api/lists.js";
import { IdbPersistence, loadAll, openDb, resetFor } from "./idb.js";
import { Replica } from "./replica.js";
import { Repository } from "./repository.js";
import { type SyncApi, SyncEngine } from "./syncEngine.js";

export interface DataStore {
  userId: string;
  replica: Replica;
  engine: SyncEngine;
  repo: Repository;
}

const CHANNEL_NAME = "dielys.replica";

const api: SyncApi = {
  memberships: getMemberships,
  changes: catchUp,
  mutate,
  claim: claimList,
  setListPosition: (request) => setListPosition(request.listId, request.position),
};

interface Control {
  closed: boolean;
  cleanups: (() => void)[];
}

interface Started {
  store: DataStore;
  db: IDBDatabase | null;
}

interface Open {
  userId: string;
  control: Control;
  ready: Promise<Started>;
}

let open: Open | null = null;

/** Idempotent per account — React StrictMode's double effect gets the same store back. */
export async function openStore(userId: string, deviceId: string): Promise<DataStore> {
  let current = open;
  if (current === null || current.userId !== userId) {
    const previous = current;
    const control: Control = { closed: false, cleanups: [] };
    // Assigned before any await, so a second call in the same tick finds it.
    current = {
      userId,
      control,
      ready: (async () => {
        if (previous !== null) await shutdown(previous, true);
        return start(userId, deviceId, control);
      })(),
    };
    open = current;
  }
  return (await current.ready).store;
}

async function start(userId: string, deviceId: string, control: Control): Promise<Started> {
  const { cleanups } = control;
  let db: IDBDatabase | null = null;
  try {
    db = await openDb();
  } catch (error) {
    console.warn("dielys: no IndexedDB, keeping data in memory only", error);
  }
  const channel =
    typeof BroadcastChannel === "undefined" ? null : new BroadcastChannel(CHANNEL_NAME);

  const replica = new Replica(
    db === null ? undefined : new IdbPersistence(db, () => channel?.postMessage("changed")),
  );
  if (db !== null) {
    const stored = await loadAll(db);
    if (stored.owner === userId) {
      replica.load(stored.data);
    } else {
      await resetFor(db, userId);
    }
  }

  const engine = new SyncEngine(replica, api);
  const repo = new Repository(replica, deviceId, () => engine.request("drain"));
  const store: DataStore = { userId, replica, engine, repo };
  cleanups.push(() => {
    engine.stop();
    replica.close();
    channel?.close();
  });
  // Signed out while this was still reading storage: hand back a stopped
  // store and wire nothing up.
  if (control.closed) return { store, db };

  if (channel !== null && db !== null) {
    const source = db;
    channel.onmessage = () => {
      void reloadFrom(source, replica, userId);
    };
  }

  const syncNow = () => engine.request("sync");
  const onVisible = () => {
    if (document.visibilityState === "visible") syncNow();
  };
  window.addEventListener("online", syncNow);
  window.addEventListener("focus", syncNow);
  document.addEventListener("visibilitychange", onVisible);
  cleanups.push(() => {
    window.removeEventListener("online", syncNow);
    window.removeEventListener("focus", syncNow);
    document.removeEventListener("visibilitychange", onVisible);
  });

  engine.request("sync");
  return { store, db };
}

/** Another tab wrote. Re-read, unless this tab wrote meanwhile — then read again. */
async function reloadFrom(db: IDBDatabase, replica: Replica, userId: string): Promise<void> {
  for (let attempt = 0; attempt < 3; attempt++) {
    const before = replica.localWrites;
    const stored = await loadAll(db);
    if (stored.owner !== userId) return;
    if (replica.localWrites === before) {
      replica.load(stored.data);
      return;
    }
  }
}

/** Signing out: stop syncing, and with `erase`, wipe this account's data from the browser. */
export async function closeStore(erase: boolean): Promise<void> {
  const closing = open;
  open = null;
  if (closing !== null) await shutdown(closing, erase);
}

async function shutdown(closing: Open, erase: boolean): Promise<void> {
  closing.control.closed = true;
  const started = await closing.ready.catch(() => null);
  for (const cleanup of closing.control.cleanups.splice(0)) cleanup();
  if (erase && started?.db) await resetFor(started.db, null).catch(() => {});
}

const DataContext = createContext<DataStore | null>(null);

export function DataProvider({
  userId,
  deviceId,
  children,
}: {
  userId: string;
  deviceId: string;
  children: ReactNode;
}) {
  const [store, setStore] = useState<DataStore | null>(null);
  useEffect(() => {
    let cancelled = false;
    openStore(userId, deviceId).then(
      (opened) => {
        if (!cancelled) setStore(opened);
      },
      (error: unknown) => console.error("dielys: could not open local data", error),
    );
    return () => {
      cancelled = true;
    };
  }, [userId, deviceId]);

  // Reading IndexedDB takes a few milliseconds; showing nothing for that
  // long reads better than a spinner that flashes.
  if (store === null || store.userId !== userId) return null;
  return <DataContext.Provider value={store}>{children}</DataContext.Provider>;
}

export function useDataStore(): DataStore {
  const store = useContext(DataContext);
  if (store === null) throw new Error("useDataStore() outside DataProvider");
  return store;
}

/** Re-renders the caller on every replica write; the value is the write counter. */
export function useReplicaVersion(replica: Replica): number {
  return useSyncExternalStore(replica.subscribe, () => replica.version);
}
