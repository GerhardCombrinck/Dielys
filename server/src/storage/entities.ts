/**
 * SQL against DO SQLite for the current state of the list and its tasks, plus
 * the per-field provenance that F5.4 resolves against. No business rules here
 * (D1) — nothing below decides whether a write should happen.
 */
import type { EntityType, Task, TaskList } from "@dielys/protocol";
import type { FieldMeta, FieldMetaMap } from "../domain/apply.js";

export function selectTask(sql: SqlStorage, id: string): Task | null {
  const rows = [
    ...sql.exec(
      `SELECT id, list_id, title, done, starred, position, deleted_at, updated_at
         FROM tasks WHERE id = ?`,
      id,
    ),
  ];
  const row = rows[0];
  if (row === undefined) return null;
  return {
    id: String(row.id),
    listId: String(row.list_id),
    title: String(row.title),
    done: Number(row.done) !== 0,
    starred: Number(row.starred) !== 0,
    position: String(row.position),
    deletedAt: row.deleted_at === null ? null : String(row.deleted_at),
    updatedAt: String(row.updated_at),
  };
}

export function upsertTask(sql: SqlStorage, task: Task): void {
  sql.exec(
    `INSERT INTO tasks (id, list_id, title, done, starred, position, deleted_at, updated_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?)
     ON CONFLICT(id) DO UPDATE SET
       title = excluded.title,
       done = excluded.done,
       starred = excluded.starred,
       position = excluded.position,
       deleted_at = excluded.deleted_at,
       updated_at = excluded.updated_at`,
    task.id,
    task.listId,
    task.title,
    task.done ? 1 : 0,
    task.starred ? 1 : 0,
    task.position,
    task.deletedAt,
    task.updatedAt,
  );
}

export function selectList(sql: SqlStorage, id: string): TaskList | null {
  const rows = [
    ...sql.exec(
      "SELECT id, title, background_photo_url, deleted_at, updated_at FROM lists WHERE id = ?",
      id,
    ),
  ];
  const row = rows[0];
  if (row === undefined) return null;
  return {
    id: String(row.id),
    title: String(row.title),
    backgroundPhotoUrl: row.background_photo_url === null ? null : String(row.background_photo_url),
    deletedAt: row.deleted_at === null ? null : String(row.deleted_at),
    updatedAt: String(row.updated_at),
  };
}

export function upsertList(sql: SqlStorage, list: TaskList): void {
  sql.exec(
    `INSERT INTO lists (id, title, background_photo_url, deleted_at, updated_at)
     VALUES (?, ?, ?, ?, ?)
     ON CONFLICT(id) DO UPDATE SET
       title = excluded.title,
       background_photo_url = excluded.background_photo_url,
       deleted_at = excluded.deleted_at,
       updated_at = excluded.updated_at`,
    list.id,
    list.title,
    list.backgroundPhotoUrl,
    list.deletedAt,
    list.updatedAt,
  );
}

export function selectFieldMeta(
  sql: SqlStorage,
  entityType: EntityType,
  entityId: string,
): FieldMetaMap {
  const cursor = sql.exec(
    `SELECT field, server_timestamp, device_id
       FROM field_meta WHERE entity_type = ? AND entity_id = ?`,
    entityType,
    entityId,
  );
  const meta: Record<string, FieldMeta> = {};
  for (const row of cursor) {
    meta[String(row.field)] = {
      serverTimestamp: String(row.server_timestamp),
      deviceId: String(row.device_id),
    };
  }
  return meta;
}

export function upsertFieldMeta(
  sql: SqlStorage,
  entityType: EntityType,
  entityId: string,
  fields: readonly string[],
  meta: FieldMeta,
): void {
  for (const field of fields) {
    sql.exec(
      `INSERT INTO field_meta (entity_type, entity_id, field, server_timestamp, device_id)
       VALUES (?, ?, ?, ?, ?)
       ON CONFLICT(entity_type, entity_id, field) DO UPDATE SET
         server_timestamp = excluded.server_timestamp,
         device_id = excluded.device_id`,
      entityType,
      entityId,
      field,
      meta.serverTimestamp,
      meta.deviceId,
    );
  }
}

export function readRoomMeta(sql: SqlStorage, key: string): string | null {
  const rows = [...sql.exec("SELECT value FROM room_meta WHERE key = ?", key)];
  const row = rows[0];
  return row === undefined ? null : String(row.value);
}

export function writeRoomMeta(sql: SqlStorage, key: string, value: string): void {
  sql.exec(
    `INSERT INTO room_meta (key, value) VALUES (?, ?)
     ON CONFLICT(key) DO UPDATE SET value = excluded.value`,
    key,
    value,
  );
}
