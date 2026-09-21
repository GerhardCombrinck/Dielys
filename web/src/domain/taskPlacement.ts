/**
 * Where the top of a list actually is, for a new item arriving there (#62).
 * Ported from `android/.../domain/TaskPlacement.kt` — see that file for the
 * full rationale (a star writes a position rather than acting as a sort key,
 * so "under the starred ones" means the run of them at the top).
 *
 * Pure — no DOM, no fetch, no clock (D1).
 */
export function spotUnderStarred<T>(active: T[], starred: (task: T) => boolean): number {
  let index = 0;
  while (index < active.length && starred(active[index] as T)) index += 1;
  return index;
}
