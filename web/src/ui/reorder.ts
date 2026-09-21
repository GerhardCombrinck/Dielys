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
 * Which slot the dragged row belongs in, given every row's box when the drag
 * began and where the dragged row's middle is now. A neighbour gives up its
 * place as soon as that middle enters its box — Android's "more than half
 * into it", which stops a slow drag oscillating between two slots.
 *
 * Its box, not its middle: the drag is clamped to the column, and at the
 * clamp the dragged middle only just *reaches* the last row's middle, so a
 * middle-past-middle rule made the last slot a pixel-perfect target.
 */
export function dropTarget(
  rects: readonly { top: number; height: number }[],
  from: number,
  middle: number,
): number {
  let target = 0;
  rects.forEach((rect, index) => {
    if (index === from) return;
    // Rows below are passed once the middle is into them; rows above stay
    // above until the middle comes back up into them.
    const above = index < from ? middle >= rect.top + rect.height : middle > rect.top;
    if (above) target++;
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
