/**
 * The short chime for ticking an item off — `public/tick_chime.wav`, drawn by
 * `docs/sound/make_chime.py`, the same file Android plays. Off when the
 * setting is (`domain/uiPrefs.ts`).
 */
import { getTickSound } from "../domain/uiPrefs.js";

let source: HTMLAudioElement | null = null;

export function playTickChime(): void {
  if (!getTickSound() || typeof Audio === "undefined") return;
  // Loaded on the first tick and kept; each play is a copy of it, so ticking
  // two items in quick succession plays two chimes instead of restarting one.
  source ??= new Audio(`${import.meta.env.BASE_URL}tick_chime.wav`);
  const chime = source.cloneNode() as HTMLAudioElement;
  // A browser that refuses (autoplay rules, no audio device) just stays quiet.
  void chime.play().catch(() => {});
}
