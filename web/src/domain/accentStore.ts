/**
 * A list's colour (#57), kept in this browser only — mirrors
 * `android/.../data/ListAccents.kt`. Not a list mutation: the colour is not
 * in `protocol/`, so the other person on a shared list keeps whatever they
 * picked.
 */
import { ACCENT_COUNT, leastUsedAccent } from "./accents.js";

const STORAGE_KEY = "dielys.accents";

function readAll(): Record<string, number> {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw === null) return {};
    const parsed: unknown = JSON.parse(raw);
    return typeof parsed === "object" && parsed !== null ? (parsed as Record<string, number>) : {};
  } catch {
    return {};
  }
}

function writeAll(accents: Record<string, number>): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(accents));
  } catch {
    // Best-effort: a browser that refuses storage just re-hashes a colour
    // every load (domain/accents.ts's `hashedAccent`) instead of keeping one.
  }
}

export function getAccent(listId: string): number | null {
  const value = readAll()[listId];
  return typeof value === "number" && value >= 0 && value < ACCENT_COUNT ? value : null;
}

/** The picker's one write path — the only place a colour already decided may
 * be overwritten. */
export function setAccent(listId: string, accent: number): void {
  const all = readAll();
  all[listId] = accent;
  writeAll(all);
}

/**
 * Gives every list in `listIds` that has no colour yet the least-used one,
 * one at a time so a batch of new lists spreads across the palette instead of
 * all taking whatever was least-used when the batch started (mirrors
 * `ListAccents.colourAsTheyArrive`).
 */
export function ensureAccents(listIds: string[]): void {
  const all = readAll();
  const usage = new Map<number, number>();
  for (const id of listIds) {
    const value = all[id];
    if (typeof value === "number") usage.set(value, (usage.get(value) ?? 0) + 1);
  }

  let changed = false;
  for (const id of listIds) {
    if (typeof all[id] === "number") continue;
    const accent = leastUsedAccent(usage);
    all[id] = accent;
    usage.set(accent, (usage.get(accent) ?? 0) + 1);
    changed = true;
  }
  if (changed) writeAll(all);
}
