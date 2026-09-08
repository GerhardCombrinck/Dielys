/**
 * SQL against DO SQLite. No business rules here (D1) — this reads and writes
 * changelog rows; it does not decide which write wins or what seq comes next.
 */
import type { ChangeEnvelope, EntityType, Task, TaskList } from "@dielys/protocol";

export interface ChangeRow {
  seq: number;
  idempotencyKey: string;
  deviceId: string;
  serverTimestamp: string;
  entityType: EntityType;
  entityJson: string;
}

export function insertChange(sql: SqlStorage, row: ChangeRow): void {
  sql.exec(
    `INSERT INTO changes (seq, idempotency_key, device_id, server_timestamp, entity_type, entity_json)
     VALUES (?, ?, ?, ?, ?, ?)`,
    row.seq,
    row.idempotencyKey,
    row.deviceId,
    row.serverTimestamp,
    row.entityType,
    row.entityJson,
  );
}

/**
 * `limit` is the caller's page size. It asks for one more row than it intends
 * to return so the caller can tell "this is the last page" from "there is
 * exactly one more", without a second COUNT query.
 */
export function selectChangesSince(sql: SqlStorage, since: number, limit: number): ChangeRow[] {
  const cursor = sql.exec(
    `SELECT seq, idempotency_key, device_id, server_timestamp, entity_type, entity_json
       FROM changes WHERE seq > ? ORDER BY seq LIMIT ?`,
    since,
    limit,
  );
  return [...cursor].map(toChangeRow);
}

export function selectChangeByIdempotencyKey(sql: SqlStorage, key: string): ChangeRow | null {
  const rows = [
    ...sql.exec(
      `SELECT seq, idempotency_key, device_id, server_timestamp, entity_type, entity_json
         FROM changes WHERE idempotency_key = ?`,
      key,
    ),
  ];
  const row = rows[0];
  return row === undefined ? null : toChangeRow(row);
}

export function maxSeq(sql: SqlStorage): number {
  const row = sql.exec("SELECT MAX(seq) AS seq FROM changes").one();
  return typeof row.seq === "number" ? row.seq : 0;
}

function toChangeRow(row: Record<string, SqlStorageValue>): ChangeRow {
  return {
    seq: Number(row.seq),
    idempotencyKey: String(row.idempotency_key),
    deviceId: String(row.device_id),
    serverTimestamp: String(row.server_timestamp),
    // Written by insertChange from a validated EntityType; SQLite has no enum
    // to carry that across the round trip.
    entityType: String(row.entity_type) as EntityType,
    entityJson: String(row.entity_json),
  };
}

/**
 * Rebuilds the envelope a client sees from a stored row. `listId` comes from
 * the room rather than the row: a ListRoom owns exactly one list, so storing
 * it per change would be a chance for the two to disagree.
 */
export function toEnvelope(row: ChangeRow, listId: string): ChangeEnvelope {
  const base = {
    seq: row.seq,
    listId,
    idempotencyKey: row.idempotencyKey,
    deviceId: row.deviceId,
    serverTimestamp: row.serverTimestamp,
  };
  // The JSON was serialized by this server from a Task or TaskList that the
  // domain layer had already produced; entity_type says which.
  if (row.entityType === "task") {
    return { ...base, entityType: "task", entity: JSON.parse(row.entityJson) as Task };
  }
  return { ...base, entityType: "list", entity: JSON.parse(row.entityJson) as TaskList };
}
