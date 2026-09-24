/**
 * Every user action, as a local write — the web port of
 * `android/.../data/DielysRepository.kt`. Each one updates the replica and
 * queues the outbox row describing the same change in one `commit` (F5.7),
 * then asks for a drain. Nothing here awaits the network: the screen shows
 * the edit in the same frame the tap happened in.
 *
 * Positions follow the same rules Android's do (F5.5); see the comments
 * there for why a star or a completion also moves the task.
 */
import type {
  ListMutation,
  ListPatch,
  NotifyEvent,
  SetListNotifyRequest,
  SetListPositionRequest,
  Task,
  TaskList,
  TaskMutation,
  TaskPatch,
} from "@dielys/protocol";
import { NOTIFY_EVENTS, PROTOCOL_VERSION } from "@dielys/protocol";
import { seedPositions } from "../domain/listOrder.js";
import { between } from "../domain/position.js";
import { activeTasks, doneTasks, sortLists } from "../domain/taskOrder.js";
import { spotUnderStarred } from "../domain/taskPlacement.js";
import { uuid7 } from "../domain/uuid7.js";
import type { LocalList, NewOutboxRow, Replica } from "./replica.js";

export class Repository {
  constructor(
    private readonly replica: Replica,
    private readonly deviceId: string,
    /** Called after every commit — the store points it at `SyncEngine.request("drain")`. */
    private readonly onCommit: () => void,
    private readonly now: () => number = Date.now,
  ) {}

  /** List ids are client-generated (F5.1): claim, then name — both queued, so this works offline too. */
  createList(title: string): string {
    const trimmed = title.trim();
    if (trimmed === "") return "";
    const id = uuid7(this.now());
    const list: TaskList = {
      id,
      title: trimmed,
      backgroundPhotoUrl: null,
      deletedAt: null,
      updatedAt: this.iso(),
    };
    this.replica.commit(
      {
        lists: [
          {
            id,
            list,
            role: "owner",
            position: null,
            memberCount: 1,
            notify: [],
            rank: Number.MAX_SAFE_INTEGER,
          },
        ],
      },
      [
        { key: uuid7(this.now()), kind: "claim", listId: id, entityId: id, body: null },
        this.listRow(id, { title: trimmed }),
      ],
    );
    this.onCommit();
    return id;
  }

  renameList(listId: string, title: string): void {
    const trimmed = title.trim();
    if (trimmed === "") return;
    this.commitList(listId, { title: trimmed });
  }

  deleteList(listId: string): void {
    this.commitList(listId, { deletedAt: this.iso() });
  }

  addTask(listId: string, title: string, atTop: boolean): string {
    const trimmed = title.trim();
    if (trimmed === "") return "";
    const active = activeTasks(this.replica.tasksIn(listId));
    let position: string;
    if (atTop) {
      const spot = spotUnderStarred(active, (t) => t.starred);
      position = between(active[spot - 1]?.position ?? null, active[spot]?.position ?? null);
    } else {
      position = between(active.at(-1)?.position ?? null, null);
    }
    const task: Task = {
      id: uuid7(this.now()),
      listId,
      title: trimmed,
      done: false,
      starred: false,
      position,
      deletedAt: null,
      updatedAt: this.iso(),
    };
    // A create MUST carry title and position — the server invents neither.
    this.commitTask(task, { title: trimmed, position });
    return task.id;
  }

  setDone(taskId: string, done: boolean): void {
    const task = this.replica.getTask(taskId);
    if (task === null) return;
    if (!done) {
      this.commitTask({ ...task, done: false }, { done: false });
      return;
    }
    const firstDone = doneTasks(this.replica.tasksIn(task.listId)).at(0) ?? null;
    const position =
      firstDone === null || firstDone.id === task.id
        ? task.position
        : between(null, firstDone.position);
    this.commitTask({ ...task, done: true, position }, { done: true, position });
  }

  setStarred(taskId: string, starred: boolean): void {
    const task = this.replica.getTask(taskId);
    if (task === null) return;
    if (!starred) {
      this.commitTask({ ...task, starred: false }, { starred: false });
      return;
    }
    const first = activeTasks(this.replica.tasksIn(task.listId)).at(0) ?? null;
    const position =
      first === null || first.id === task.id ? task.position : between(null, first.position);
    this.commitTask({ ...task, starred: true, position }, { starred: true, position });
  }

  renameTask(taskId: string, title: string): void {
    const task = this.replica.getTask(taskId);
    const trimmed = title.trim();
    if (task === null || trimmed === "" || trimmed === task.title) return;
    this.commitTask({ ...task, title: trimmed }, { title: trimmed });
  }

  /** A tombstone, never a delete (F5.3). */
  deleteTask(taskId: string): void {
    const task = this.replica.getTask(taskId);
    if (task === null) return;
    const deletedAt = this.iso();
    this.commitTask({ ...task, deletedAt }, { deletedAt });
  }

  /** One row written, siblings never renumbered (F5.5, H3.9). */
  moveTask(taskId: string, afterId: string | null, beforeId: string | null): void {
    const task = this.replica.getTask(taskId);
    if (task === null) return;
    const after = afterId !== null ? this.replica.getTask(afterId) : null;
    const before = beforeId !== null ? this.replica.getTask(beforeId) : null;
    const position = between(after?.position ?? null, before?.position ?? null);
    this.commitTask({ ...task, position }, { position });
  }

  /**
   * This account's own order, not a list mutation (PROTOCOL.md "Ordering the
   * lists"). The first drag seeds every keyless list in the order it is
   * already shown, same as Android's `moveList`.
   */
  moveList(listId: string, afterId: string | null, beforeId: string | null): void {
    const ordered = sortLists(this.replica.allLists());
    if (!ordered.some((l) => l.id === listId)) return;

    const seeded = seedPositions(
      ordered,
      (l) => l.position,
      (l, position) => ({ ...l, position }),
    );
    const byId = new Map(seeded.map((l) => [l.id, l]));
    const moved = byId.get(listId);
    if (moved === undefined) return;
    const after = afterId !== null ? (byId.get(afterId) ?? null) : null;
    const before = beforeId !== null ? (byId.get(beforeId) ?? null) : null;
    const position = between(after?.position ?? null, before?.position ?? null);

    const unseeded = new Set(ordered.filter((l) => l.position === null).map((l) => l.id));
    const seedWrites = seeded.filter((l) => unseeded.has(l.id) && l.id !== listId);
    const writes: LocalList[] = [...seedWrites, { ...moved, position }];

    this.replica.commit(
      { lists: writes },
      writes.map((l) => this.orderRow(l.id, l.position as string)),
    );
    this.onCommit();
  }

  /**
   * Which changes on this list this account wants a phone notification for
   * (ADR 0012) — the whole set; empty turns it off. This account's own
   * membership row, queued like a drag, never a list mutation. The row's
   * entity id is not the list's, so it never shadows the list's own changes.
   */
  setNotify(listId: string, events: NotifyEvent[]): void {
    const known = this.replica.getList(listId);
    if (known === null) return;
    const chosen = NOTIFY_EVENTS.filter((e) => events.includes(e));
    const body: SetListNotifyRequest = { listId, events: chosen };
    this.replica.commit({ lists: [{ ...known, notify: chosen }] }, [
      { key: uuid7(this.now()), kind: "notify", listId, entityId: `notify:${listId}`, body },
    ]);
    this.onCommit();
  }

  private commitTask(task: Task, patch: TaskPatch): void {
    const mutation: TaskMutation = {
      type: "mutate",
      protocolVersion: PROTOCOL_VERSION,
      listId: task.listId,
      idempotencyKey: uuid7(this.now()),
      deviceId: this.deviceId,
      entityId: task.id,
      entityType: "task",
      patch,
    };
    this.replica.commit({ tasks: [task] }, [
      {
        key: mutation.idempotencyKey,
        kind: "task",
        listId: task.listId,
        entityId: task.id,
        body: mutation,
      },
    ]);
    this.onCommit();
  }

  private commitList(listId: string, patch: ListPatch): void {
    const known = this.replica.getList(listId);
    if (known === null || known.list === null) return;
    const list: TaskList = { ...known.list, ...patch };
    this.replica.commit({ lists: [{ ...known, list }] }, [this.listRow(listId, patch)]);
    this.onCommit();
  }

  private listRow(listId: string, patch: ListPatch): NewOutboxRow {
    const mutation: ListMutation = {
      type: "mutate",
      protocolVersion: PROTOCOL_VERSION,
      listId,
      idempotencyKey: uuid7(this.now()),
      deviceId: this.deviceId,
      entityId: listId,
      entityType: "list",
      patch,
    };
    return { key: mutation.idempotencyKey, kind: "list", listId, entityId: listId, body: mutation };
  }

  private orderRow(listId: string, position: string): NewOutboxRow {
    const body: SetListPositionRequest = { listId, position };
    return { key: uuid7(this.now()), kind: "order", listId, entityId: listId, body };
  }

  private iso(): string {
    return new Date(this.now()).toISOString();
  }
}
