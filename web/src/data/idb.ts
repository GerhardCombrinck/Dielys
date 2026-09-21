/**
 * Where the replica survives a reload: one IndexedDB database per browser
 * profile, written through `Replica.transact` one `WriteBatch` per IndexedDB
 * transaction — so an entity and its outbox row land together or not at all
 * (F5.7), exactly as Room's `withTransaction` makes them on Android.
 *
 * Schema changes go through `onupgradeneeded` as numbered steps that keep
 * what is there. Never delete and recreate a store to "migrate" it: the
 * outbox lives here, and wiping it is unsynced user data lost with no error
 * shown — the same reason G2 bans `fallbackToDestructiveMigration()`.
 *
 * No wrapper library (N1): four stores and one write shape do not earn one.
 */
import type { Task } from "@dielys/protocol";
import type {
  ListCursor,
  LocalList,
  OutboxRow,
  Persistence,
  ReplicaData,
  WriteBatch,
} from "./replica.js";

const DB_NAME = "dielys";
const DB_VERSION = 1;
const STORES = ["lists", "tasks", "cursors", "outbox", "meta"] as const;
const OWNER_KEY = "owner";

function promised<T>(request: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

function completed(tx: IDBTransaction): Promise<void> {
  return new Promise((resolve, reject) => {
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error);
  });
}

export function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION);
    request.onupgradeneeded = (event) => {
      const db = request.result;
      if (event.oldVersion < 1) {
        db.createObjectStore("lists", { keyPath: "id" });
        db.createObjectStore("tasks", { keyPath: "id" }).createIndex("listId", "listId");
        db.createObjectStore("cursors", { keyPath: "listId" });
        db.createObjectStore("outbox", { keyPath: "key" });
        db.createObjectStore("meta");
      }
    };
    request.onsuccess = () => {
      const db = request.result;
      // A newer build opened in another tab wants to upgrade: step aside
      // rather than block it. This tab's next write fails loudly, and a
      // reload picks up the new build.
      db.onversionchange = () => db.close();
      resolve(db);
    };
    request.onerror = () => reject(request.error);
    // Another tab is holding an older version open; it closes itself on
    // `versionchange` below, and this open proceeds once it has.
    request.onblocked = () => {};
  });
}

/** Everything stored, plus whose it is. */
export async function loadAll(
  db: IDBDatabase,
): Promise<{ owner: string | null; data: ReplicaData }> {
  const tx = db.transaction(STORES, "readonly");
  const [lists, tasks, cursors, outbox, owner] = await Promise.all([
    promised(tx.objectStore("lists").getAll() as IDBRequest<LocalList[]>),
    promised(tx.objectStore("tasks").getAll() as IDBRequest<Task[]>),
    promised(tx.objectStore("cursors").getAll() as IDBRequest<ListCursor[]>),
    promised(tx.objectStore("outbox").getAll() as IDBRequest<OutboxRow[]>),
    promised(tx.objectStore("meta").get(OWNER_KEY) as IDBRequest<string | undefined>),
  ]);
  return { owner: owner ?? null, data: { lists, tasks, cursors, outbox } };
}

/** Empties every store and records the new owner, in one transaction. */
export async function resetFor(db: IDBDatabase, owner: string | null): Promise<void> {
  const tx = db.transaction(STORES, "readwrite");
  for (const name of STORES) tx.objectStore(name).clear();
  if (owner !== null) tx.objectStore("meta").put(owner, OWNER_KEY);
  await completed(tx);
}

export class IdbPersistence implements Persistence {
  constructor(
    private readonly db: IDBDatabase,
    /** Told after every committed write, so other tabs reload (`store.ts`). */
    private readonly onCommitted: () => void,
  ) {}

  write(batch: WriteBatch): void {
    const tx = this.db.transaction(STORES, "readwrite");
    const lists = tx.objectStore("lists");
    const tasks = tx.objectStore("tasks");
    const cursors = tx.objectStore("cursors");
    const outbox = tx.objectStore("outbox");

    // A batch never forgets a list and writes to it too (`noteMemberships`
    // only forgets lists it is not writing), which matters here: the task
    // deletes below run a callback later, after every put in this batch.
    for (const listId of batch.forgetLists) {
      lists.delete(listId);
      cursors.delete(listId);
      const keys = tasks.index("listId").getAllKeys(IDBKeyRange.only(listId));
      keys.onsuccess = () => {
        for (const key of keys.result) tasks.delete(key);
      };
    }
    for (const list of batch.putLists) lists.put(list);
    for (const task of batch.putTasks) tasks.put(task);
    for (const cursor of batch.putCursors) cursors.put(cursor);
    for (const row of batch.putOutbox) outbox.put(row);
    for (const key of batch.deleteOutbox) outbox.delete(key);

    completed(tx).then(this.onCommitted, (error: unknown) => {
      // Memory already holds the edit, so the screen is right for this tab;
      // what is lost is surviving a reload. Nothing better to do than say so.
      console.error("dielys: local write failed", error);
    });
  }
}
