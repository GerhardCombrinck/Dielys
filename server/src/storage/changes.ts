/**
 * SQL against DO SQLite. No business rules here (D1) — this reads and writes
 * changelog rows; it does not decide which write wins or what seq comes next.
 */

export interface ChangeRow {
  seq: number;
  idempotencyKey: string;
  deviceId: string;
  serverTimestamp: string;
  entityJson: string;
}

export function insertChange(_sql: SqlStorage, _row: ChangeRow): void {
  // TODO: INSERT INTO changes (...) VALUES (...) inside the caller's transaction.
  throw new Error("not implemented");
}

export function selectChangesSince(_sql: SqlStorage, _since: number): ChangeRow[] {
  // TODO: SELECT * FROM changes WHERE seq > ?since ORDER BY seq.
  throw new Error("not implemented");
}
