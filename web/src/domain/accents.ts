/**
 * A list's colour (#57) — local to this browser, same as
 * `android/.../data/ListAccents.kt`. Not in `protocol/`: the colour is not
 * list data, so the other person on a shared list picks their own.
 *
 * The palette and the least-used rule are ported from
 * `android/.../domain/Accents.kt` and `android/.../ui/theme/ListAccent.kt` —
 * eight mid-tone colours, far apart on the wheel, that read on both the Sand
 * and Navy backgrounds.
 *
 * Pure — no DOM, no fetch, no clock (D1).
 */
export const ACCENT_COUNT = 8;

const ACCENT_COLORS = [
  "#E8A33D", // amber
  "#7FA893", // sage
  "#7C93C4", // dusty blue
  "#C97B63", // terracotta
  "#C77B94", // rose
  "#5FA6A4", // teal
  "#9B8AC9", // lilac
  "#A9A85C", // olive
] as const;

if (ACCENT_COLORS.length !== ACCENT_COUNT) {
  throw new Error("the palette must have ACCENT_COUNT colours");
}

/** The colour for a stored palette index. Wrapped, not bounds-checked, so a
 * shorter future palette still shows *a* colour rather than throwing. */
export function accentColor(index: number): string {
  const wrapped = ((index % ACCENT_COLORS.length) + ACCENT_COLORS.length) % ACCENT_COLORS.length;
  return ACCENT_COLORS[wrapped] as string;
}

/**
 * Whichever colour the fewest live lists already wear. Ties go to the lowest
 * index, which is what makes the first few lists on a fresh browser come out
 * amber, sage, dusty blue, … in that order rather than at random.
 */
export function leastUsedAccent(usage: Map<number, number>): number {
  let best = 0;
  let bestCount = usage.get(0) ?? 0;
  for (let index = 1; index < ACCENT_COUNT; index += 1) {
    const count = usage.get(index) ?? 0;
    if (count < bestCount) {
      best = index;
      bestCount = count;
    }
  }
  return best;
}

/** A stable hash of the id, for a list this browser has not coloured yet
 * (still hydrating, or predates the feature). Not a replacement for a stored
 * colour — see `leastUsedAccent`. */
export function hashedAccent(listId: string): number {
  let hash = 0;
  for (let i = 0; i < listId.length; i += 1) {
    hash = (hash * 31 + listId.charCodeAt(i)) | 0;
  }
  return accentIndexFromHash(hash);
}

function accentIndexFromHash(hash: number): number {
  return ((hash % ACCENT_COUNT) + ACCENT_COUNT) % ACCENT_COUNT;
}
