/**
 * This browser's copy of every list it can see, plus the outbox of edits the
 * server has not acknowledged yet — the web counterpart of Android's Room
 * database (`android/.../data/local/`) and `ChangeApplier.kt`.
 *
 * Everything the UI shows is read from here, and every tap is written here
 * first, synchronously, so the screen never waits on the network
 * (docs/SYNC.md "local-first"). `SyncEngine` is what later makes the server
 * agree.
 *
 * Memory is the working copy; `Persistence` (IndexedDB in the app,
 * `data/idb.ts`) is where it survives a reload. Every `transact` block
 * becomes exactly one persistence write, which is how F5.7 (entity and
 * outbox row commit together) and F5.8 (the cursor moves in the same write
 * as the change it covers) hold here the way `withTransaction` makes them
 * hold on Android.
 */
import type {
  ChangeEnvelope,
  Membership,
  MembershipRole,
  Mutation,
  SetListPositionRequest,
  Task,
  TaskList,
} from "@dielys/protocol";

/** A list as this browser knows it: the changelog's half and the membership's half. */
export interface LocalList {
  id: string;
  /** Null until the changelog (or a create made here) has named it. */
  list: TaskList | null;
  /** Null for a list reached through its changelog before `/auth/memberships` answered. */
  role: MembershipRole | null;
  /** This account's own order (PROTOCOL.md "Ordering the lists"). */
  position: string | null;
  memberCount: number;
  /** Where the last memberships answer put it — the server's tie-break for
   * rows without a position, which this browser cannot recompute on its own. */
  rank: number;
}

export type OutboxKind = "task" | "list" | "claim" | "order";

export interface OutboxRow {
  /** Generated once, when the user acted, and resent unchanged on every retry (F5.2). */
  key: string;
  /** Drain order: oldest first, one at a time (`SyncEngine.drain`). */
  seq: number;
  kind: OutboxKind;
  listId: string;
  entityId: string;
  /** The stored request, sent byte-for-byte — never rebuilt on retry. Null for a claim. */
  body: Mutation | SetListPositionRequest | null;
  /** Why the server refused it for good, or null while it can still go. */
  dead: string | null;
}

export type NewOutboxRow = Omit<OutboxRow, "seq" | "dead">;

export interface ListCursor {
  listId: string;
  cursor: number;
}

export interface ReplicaData {
  lists: LocalList[];
  tasks: Task[];
  cursors: ListCursor[];
  outbox: OutboxRow[];
}

/** One atomic write — everything a single `transact` block changed. */
export interface WriteBatch {
  putLists: LocalList[];
  putTasks: Task[];
  putCursors: ListCursor[];
  putOutbox: OutboxRow[];
  deleteOutbox: string[];
  /** Drop the list, its tasks and its cursor. */
  forgetLists: string[];
}

export interface Persistence {
  write(batch: WriteBatch): void;
}

/** What `apply` did with a change — same three answers as `ChangeApplier.kt`. */
export type ApplyOutcome =
  /** Written, and the cursor advanced past it. */
  | "applied"
  /** Already at or past this seq. Redelivery is a no-op, not an error (F5.2). */
  | "already-applied"
  /** A seq beyond `cursor + 1`. Nothing written, cursor unmoved: pull the missing range (F5.6). */
  | "gap";

/** Stands in for a persistence layer that is not there (tests, or a browser that refuses IndexedDB). */
export const NO_PERSISTENCE: Persistence = { write() {} };

function emptyBatch(): WriteBatch {
  return {
    putLists: [],
    putTasks: [],
    putCursors: [],
    putOutbox: [],
    deleteOutbox: [],
    forgetLists: [],
  };
}

function isEmpty(batch: WriteBatch): boolean {
  return (
    batch.putLists.length === 0 &&
    batch.putTasks.length === 0 &&
    batch.putCursors.length === 0 &&
    batch.putOutbox.length === 0 &&
    batch.deleteOutbox.length === 0 &&
    batch.forgetLists.length === 0
  );
}

export class Replica {
  private lists = new Map<string, LocalList>();
  private tasks = new Map<string, Task>();
  private cursors = new Map<string, number>();
  private outbox = new Map<string, OutboxRow>();
  private nextSeq = 1;
  private batch: WriteBatch | null = null;
  private closed = false;
  private readonly listeners = new Set<() => void>();
  private currentVersion = 0;
  /** Set once the first `/auth/memberships` answer has landed this session —
   * before that, an empty replica means "not asked yet", not "no lists". */
  private membershipsKnown = false;
  /** Bumped by every persisted write this tab makes. A reload from storage
   * (another tab wrote) compares it before and after reading, so it never
   * swaps in a snapshot that predates a write this tab made meanwhile. */
  localWrites = 0;

  constructor(private readonly persistence: Persistence = NO_PERSISTENCE) {}

  /** Changes on every write; what `useSyncExternalStore` compares. */
  get version(): number {
    return this.currentVersion;
  }

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };

  /** Replaces everything held in memory with what storage has. */
  load(data: ReplicaData): void {
    this.lists = new Map(data.lists.map((l) => [l.id, l]));
    this.tasks = new Map(data.tasks.map((t) => [t.id, t]));
    this.cursors = new Map(data.cursors.map((c) => [c.listId, c.cursor]));
    this.outbox = new Map(data.outbox.map((r) => [r.key, r]));
    this.nextSeq = data.outbox.reduce((max, r) => Math.max(max, r.seq), 0) + 1;
    this.changed();
  }

  /** Signed out: nothing after this reaches storage, however late it lands. */
  close(): void {
    this.closed = true;
  }

  // --- Reads ------------------------------------------------------------

  getList(id: string): LocalList | null {
    return this.lists.get(id) ?? null;
  }

  allLists(): LocalList[] {
    return [...this.lists.values()];
  }

  getTask(id: string): Task | null {
    return this.tasks.get(id) ?? null;
  }

  /** Every task in the list, tombstones included — callers filter. */
  tasksIn(listId: string): Task[] {
    return [...this.tasks.values()].filter((t) => t.listId === listId);
  }

  cursor(listId: string): number {
    return this.cursors.get(listId) ?? 0;
  }

  hasCursor(listId: string): boolean {
    return this.cursors.has(listId);
  }

  get hasMemberships(): boolean {
    return this.membershipsKnown;
  }

  /** The oldest row still able to go, or null when the outbox is drained. */
  nextPending(): OutboxRow | null {
    let next: OutboxRow | null = null;
    for (const row of this.outbox.values()) {
      if (row.dead === null && (next === null || row.seq < next.seq)) next = row;
    }
    return next;
  }

  pendingCount(): number {
    let n = 0;
    for (const row of this.outbox.values()) if (row.dead === null) n++;
    return n;
  }

  /** Edits the server refused for good. Surfaced so they are not silent. */
  stuckCount(): number {
    let n = 0;
    for (const row of this.outbox.values()) if (row.dead !== null) n++;
    return n;
  }

  /** A list made here that the server has not accepted as ours yet — nothing to pull or listen to. */
  isUnclaimed(listId: string): boolean {
    for (const row of this.outbox.values()) {
      if (row.kind === "claim" && row.listId === listId) return true;
    }
    return false;
  }

  hasOutboxFor(listId: string): boolean {
    for (const row of this.outbox.values()) if (row.listId === listId) return true;
    return false;
  }

  // --- Writes -----------------------------------------------------------

  /**
   * Runs `fn` as one atomic write. Reentrant, like Room's `withTransaction`:
   * a block already inside one (the drain applying an ack and deleting its
   * row) joins it rather than starting a second.
   */
  transact<T>(fn: () => T): T {
    if (this.batch !== null) return fn();
    this.batch = emptyBatch();
    let result: T;
    try {
      result = fn();
    } finally {
      const batch = this.batch;
      this.batch = null;
      if (!isEmpty(batch) && !this.closed) {
        this.persistence.write(batch);
        this.localWrites++;
      }
    }
    this.changed();
    return result;
  }

  /** A local edit: the entity and the outbox rows describing it, together (F5.7). */
  commit(entities: { lists?: LocalList[]; tasks?: Task[] }, rows: NewOutboxRow[]): void {
    this.transact(() => {
      for (const list of entities.lists ?? []) this.putList(list);
      for (const task of entities.tasks ?? []) this.putTask(task);
      for (const row of rows) this.putRow({ ...row, seq: this.nextSeq++, dead: null });
    });
  }

  /**
   * The single place a server change becomes local state — socket push,
   * `?since=` catch-up and mutation ack all come through here (F5.6), and
   * the cursor only ever moves in the same write as the change (F5.8).
   */
  apply(change: ChangeEnvelope): ApplyOutcome {
    return this.transact(() => {
      const cursor = this.cursor(change.listId);
      if (change.seq <= cursor) return "already-applied";
      if (change.seq !== cursor + 1) return "gap";
      this.write(change);
      this.putCursor(change.listId, change.seq);
      return "applied";
    });
  }

  /** A page of catch-up in one write rather than one per change. */
  applyAll(changes: ChangeEnvelope[]): void {
    this.transact(() => {
      for (const change of changes) this.apply(change);
    });
  }

  /** The server acknowledged `key`: apply its echo and drop the row, together. */
  completeRow(key: string, ack: ChangeEnvelope | null): ApplyOutcome | null {
    return this.transact(() => {
      const outcome = ack === null ? null : this.apply(ack);
      this.deleteRow(key);
      return outcome;
    });
  }

  /** Marked, never deleted: dropping something the user typed is worse than leaving it stuck. */
  markDead(key: string, reason: string): void {
    const row = this.outbox.get(key);
    if (row === undefined) return;
    this.transact(() => this.putRow({ ...row, dead: reason }));
  }

  /**
   * Folds in an `/auth/memberships` answer — `SyncEngine.discover` on
   * Android. New lists appear unnamed until their changelog is pulled; a list
   * this account is no longer on is forgotten (#60), unless it still has
   * something in the outbox: a list made offline is correctly absent from
   * the answer until its claim lands, and forgetting it would throw away what
   * somebody just typed.
   */
  noteMemberships(memberships: Membership[]): void {
    this.transact(() => {
      memberships.forEach((m, rank) => {
        const known = this.lists.get(m.listId);
        // A drag queued after the drain started is newer than whatever this
        // answer says — keep it until its own row lands.
        const dragPending = [...this.outbox.values()].some(
          (r) => r.kind === "order" && r.listId === m.listId && r.dead === null,
        );
        this.putList({
          id: m.listId,
          list: known?.list ?? null,
          role: m.role,
          position: dragPending ? (known?.position ?? null) : m.position,
          memberCount: m.memberCount,
          rank,
        });
      });
      const mine = new Set(memberships.map((m) => m.listId));
      for (const id of this.lists.keys()) {
        if (!mine.has(id) && !this.hasOutboxFor(id)) this.forget(id);
      }
    });
    this.membershipsKnown = true;
    this.changed();
  }

  // --- Internals --------------------------------------------------------

  /**
   * The envelope is the whole entity as the server resolved it, so there is
   * no merge to do — F5.4 happened server-side. One exception, same as
   * `ChangeApplier.kt`: if this browser still has another edit to the same
   * entity queued, this change is an echo of a tap the user already moved
   * past. Writing it would flash the old value until the last echo lands, so
   * the write (not the cursor advance) is skipped; the last echo has nothing
   * left to shadow it and leaves the server's value behind.
   */
  private write(change: ChangeEnvelope): void {
    const entityId = change.entity.id;
    for (const row of this.outbox.values()) {
      if (
        row.entityId === entityId &&
        row.key !== change.idempotencyKey &&
        row.dead === null &&
        (row.kind === "task" || row.kind === "list")
      ) {
        return;
      }
    }
    if (change.entityType === "task") {
      this.putTask(change.entity);
      return;
    }
    // Role, order and member count come from /auth/memberships, not the
    // changelog — keep whatever is already known (#68).
    const known = this.lists.get(entityId);
    this.putList({
      id: entityId,
      list: change.entity,
      role: known?.role ?? null,
      position: known?.position ?? null,
      memberCount: known?.memberCount ?? 1,
      rank: known?.rank ?? Number.MAX_SAFE_INTEGER,
    });
  }

  private requireBatch(): WriteBatch {
    if (this.batch === null) throw new Error("replica write outside transact()");
    return this.batch;
  }

  private putList(list: LocalList): void {
    this.lists.set(list.id, list);
    this.requireBatch().putLists.push(list);
  }

  private putTask(task: Task): void {
    this.tasks.set(task.id, task);
    this.requireBatch().putTasks.push(task);
  }

  private putCursor(listId: string, cursor: number): void {
    this.cursors.set(listId, cursor);
    this.requireBatch().putCursors.push({ listId, cursor });
  }

  private putRow(row: OutboxRow): void {
    this.outbox.set(row.key, row);
    this.requireBatch().putOutbox.push(row);
  }

  private deleteRow(key: string): void {
    this.outbox.delete(key);
    this.requireBatch().deleteOutbox.push(key);
  }

  private forget(listId: string): void {
    this.lists.delete(listId);
    this.cursors.delete(listId);
    for (const [id, task] of this.tasks) if (task.listId === listId) this.tasks.delete(id);
    this.requireBatch().forgetLists.push(listId);
  }

  private changed(): void {
    this.currentVersion++;
    for (const listener of this.listeners) listener();
  }
}
