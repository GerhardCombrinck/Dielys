/**
 * Pure. Decides what a patch does to an entity — the whole of F5.3, F5.4 and
 * the create rules live here, with no storage, no env, and no clock (D1). The
 * server timestamp is passed in by the caller, which is what lets every rule
 * below be tested without a runtime.
 */
import type { ErrorCode, ListPatch, Task, TaskList, TaskPatch } from "@dielys/protocol";
import { incomingWins, type Timestamped } from "./conflict.js";

/** Provenance of one field: who last wrote it, and when by the server's clock. */
export type FieldMeta = Timestamped;

/** field name -> provenance. A missing field has never been written. */
export type FieldMetaMap = Readonly<Record<string, FieldMeta>>;

export type ApplyResult<T> =
  | { ok: true; entity: T; wrote: readonly string[] }
  | { ok: false; code: ErrorCode };

/**
 * Applies `patch` to `current`, field by field.
 *
 * Returns the resulting entity even when every field lost its contest — the
 * mutation was still accepted, and the client needs the authoritative result
 * (F5.2 wants a redelivery to return the original outcome, which means every
 * accepted mutation has one). `wrote` names the fields that actually changed
 * hands, so the caller knows whose provenance to update.
 */
export function applyTaskPatch(
  current: Task | null,
  currentMeta: FieldMetaMap,
  patch: TaskPatch,
  ids: { entityId: string; listId: string },
  meta: FieldMeta,
): ApplyResult<Task> {
  if (current === null) {
    const { title, position } = patch;
    if (title === undefined || position === undefined) {
      return { ok: false, code: "incomplete-create" };
    }
    const created: Task = {
      id: ids.entityId,
      listId: ids.listId,
      title,
      done: patch.done ?? false,
      starred: patch.starred ?? false,
      position,
      deletedAt: patch.deletedAt ?? null,
      updatedAt: meta.serverTimestamp,
    };
    return { ok: true, entity: created, wrote: Object.keys(patch) };
  }

  const next: Task = { ...current };
  const wrote: string[] = [];

  function take<K extends "title" | "done" | "starred" | "position" | "deletedAt">(
    field: K,
    incoming: Task[K] | undefined,
  ): void {
    if (incoming === undefined) return;
    if (!fieldChangeWins(currentMeta[field], meta)) return;
    next[field] = incoming;
    wrote.push(field);
  }

  take("title", patch.title);
  take("done", patch.done);
  take("starred", patch.starred);
  take("position", patch.position);

  // A tombstone is sticky (F5.3). An update racing a delete may still land its
  // other fields, but it never resurrects the row — that is the defined winner
  // H3.7 asks for, and plain LWW would instead let arrival order decide, which
  // is the bug.
  if (!(patch.deletedAt === null && current.deletedAt !== null)) {
    take("deletedAt", patch.deletedAt);
  }

  if (wrote.length > 0) next.updatedAt = meta.serverTimestamp;
  return { ok: true, entity: next, wrote };
}

export function applyListPatch(
  current: TaskList | null,
  currentMeta: FieldMetaMap,
  patch: ListPatch,
  ids: { entityId: string },
  meta: FieldMeta,
): ApplyResult<TaskList> {
  if (current === null) {
    if (patch.title === undefined) return { ok: false, code: "incomplete-create" };
    const created: TaskList = {
      id: ids.entityId,
      title: patch.title,
      backgroundPhotoUrl: patch.backgroundPhotoUrl ?? null,
      deletedAt: patch.deletedAt ?? null,
      updatedAt: meta.serverTimestamp,
    };
    return { ok: true, entity: created, wrote: Object.keys(patch) };
  }

  const next: TaskList = { ...current };
  const wrote: string[] = [];

  function take<K extends "title" | "backgroundPhotoUrl" | "deletedAt">(
    field: K,
    incoming: TaskList[K] | undefined,
  ): void {
    if (incoming === undefined) return;
    if (!fieldChangeWins(currentMeta[field], meta)) return;
    next[field] = incoming;
    wrote.push(field);
  }

  take("title", patch.title);
  take("backgroundPhotoUrl", patch.backgroundPhotoUrl);

  // Sticky tombstone — see applyTaskPatch.
  if (!(patch.deletedAt === null && current.deletedAt !== null)) {
    take("deletedAt", patch.deletedAt);
  }

  if (wrote.length > 0) next.updatedAt = meta.serverTimestamp;
  return { ok: true, entity: next, wrote };
}

/** A field nobody has written yet is always lost by the absent writer. */
function fieldChangeWins(current: FieldMeta | undefined, incoming: FieldMeta): boolean {
  if (current === undefined) return true;
  return incomingWins(current, incoming);
}
