/**
 * Seeding a list order that nobody has set yet. Ported from
 * `android/.../domain/ListOrder.kt` — see that file for the full rationale.
 *
 * Lists made before per-account ordering existed have no key at all, and
 * neither does one that arrived by invite. Rather than invent an order for
 * the whole screen at startup, the first drag gives every keyless row a key
 * **in the order it is already shown**, so what the person sees before the
 * drag is what they see after it, minus the row they moved.
 *
 * Pure — no DOM, no fetch, no clock (D1).
 */
import { between } from "./position.js";

export function seedPositions<T>(
  ordered: T[],
  positionOf: (row: T) => string | null,
  withPosition: (row: T, position: string) => T,
): T[] {
  if (ordered.every((row) => positionOf(row) !== null)) return ordered;
  let previous: string | null = null;
  return ordered.map((row) => {
    const existing = positionOf(row);
    const position = existing ?? between(previous, null);
    previous = position;
    return existing === position ? row : withPosition(row, position);
  });
}
