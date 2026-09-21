/**
 * The pure half of drag-to-reorder (`useDragReorder.ts` is the gesture) — a
 * port of `android/.../ui/reorder/Reorder.kt`'s `moved` and
 * `draftStillWanted`, plus the one decision the web gesture makes that
 * Compose's `LazyColumn` made for Android: which slot the dragged row is over.
 */

/** Takes the item at [from] and puts it at [to], everything else closing up behind it. */
export function moved<T>(list: readonly T[], from: number, to: number): T[] {
  if (from === to || from < 0 || to < 0 || from >= list.length || to >= list.length) {
    return [...list];
  }
  const copy = [...list];
  const [item] = copy.splice(from, 1);
  copy.splice(to, 0, item as T);
  return copy;
}

/**
 * The `(afterId, beforeId)` a row at [index] of [order] sits between — what
 * `moveList`/`useTaskBoard().move` expect, and `domain/position.ts` places
 * between. Null on either side is an end of the list.
 */
export function neighborsOf(
  order: readonly string[],
  index: number,
): { afterId: string | null; beforeId: string | null } {
  return {
    afterId: order[index - 1] ?? null,
    beforeId: order[index + 1] ?? null,
  };
}

/**
 * Which slot the dragged row belongs in, given where every row's middle sat
 * when the drag began and where the dragged row's middle is now. A row gives
 * up its place once the dragged middle passes its own middle — the web
 * equivalent of Android's "more than half into it", which is what stops a
 * slow drag oscillating between two slots.
 */
export function dropTarget(centers: readonly number[], from: number, middle: number): number {
  let target = 0;
  centers.forEach((center, index) => {
    if (index !== from && center < middle) target++;
  });
  return target;
}

/**
 * Whether the order a drag produced should still be shown instead of what the
 * server says. The draft wins until the server agrees with it, and is dropped
 * the moment the two are about different rows — something else happened (a
 * row added, a list shared away) and the draft is stale rather than ahead.
 */
export function draftStillWanted(draft: readonly string[], stored: readonly string[]): boolean {
  if (draft.length !== stored.length) return false;
  const storedSet = new Set(stored);
  return draft.some((id, i) => id !== stored[i]) && draft.every((id) => storedSet.has(id));
}
