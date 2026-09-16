/**
 * Turns a drag from one index to another into the `(afterId, beforeId)`
 * neighbours `moveList`/`useTaskBoard().move` expect — the position write
 * itself lives in `domain/position.ts`; this only works out which two ids the
 * dropped row landed between.
 */
export function dropNeighbors(
  ids: string[],
  fromIndex: number,
  toIndex: number,
): { id: string; afterId: string | null; beforeId: string | null } {
  const working = [...ids];
  const [id] = working.splice(fromIndex, 1);
  if (id === undefined) throw new Error("fromIndex out of range");
  const clamped = toIndex > fromIndex ? toIndex - 1 : toIndex;
  working.splice(clamped, 0, id);
  const at = working.indexOf(id);
  return {
    id,
    afterId: at > 0 ? (working[at - 1] ?? null) : null,
    beforeId: at < working.length - 1 ? (working[at + 1] ?? null) : null,
  };
}
