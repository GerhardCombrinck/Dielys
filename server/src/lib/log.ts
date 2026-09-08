/**
 * Structured JSON logging. No bare console.log in product code (D4) — logs
 * must be searchable and must never contain task titles, list names, or note
 * bodies. Log ids, not content.
 */

type Level = "debug" | "info" | "warn" | "error";

export function log(level: Level, message: string, fields: Record<string, unknown> = {}) {
  console.log(JSON.stringify({ level, message, ...fields, ts: new Date().toISOString() }));
}
