/**
 * Small display choices that belong to this browser, not to a list — the web
 * counterpart to `android/.../data/local/UiPrefs.kt`'s `newItemsOnTop` and
 * `tickSound`. Never synced: two people on the same shared list may set these
 * differently.
 */
const NEW_ITEMS_ON_TOP_KEY = "dielys.newItemsOnTop";
const TICK_SOUND_KEY = "dielys.tickSound";

/** Defaults to top, matching Android's own default. */
export function getNewItemsOnTop(): boolean {
  try {
    const raw = localStorage.getItem(NEW_ITEMS_ON_TOP_KEY);
    return raw === null ? true : raw === "1";
  } catch {
    return true;
  }
}

export function setNewItemsOnTop(value: boolean): void {
  try {
    localStorage.setItem(NEW_ITEMS_ON_TOP_KEY, value ? "1" : "0");
  } catch {
    // Best-effort — the choice just does not stick past this page load.
  }
}

/** Whether ticking an item off plays the chime. On by default, as on Android. */
export function getTickSound(): boolean {
  try {
    return localStorage.getItem(TICK_SOUND_KEY) !== "0";
  } catch {
    return true;
  }
}

export function setTickSound(value: boolean): void {
  try {
    localStorage.setItem(TICK_SOUND_KEY, value ? "1" : "0");
  } catch {
    // Best-effort — the choice just does not stick past this page load.
  }
}
